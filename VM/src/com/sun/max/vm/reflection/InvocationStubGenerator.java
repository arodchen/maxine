/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.reflection;

import static com.sun.max.vm.classfile.constant.PoolConstantFactory.*;
import static com.sun.max.vm.type.ClassRegistry.Property.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import com.sun.max.annotate.*;
import com.sun.max.collect.*;
import com.sun.max.io.*;
import com.sun.max.lang.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.bytecode.graft.*;
import com.sun.max.vm.bytecode.graft.BytecodeAssembler.*;
import com.sun.max.vm.classfile.*;
import com.sun.max.vm.classfile.ClassfileWriter.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.value.*;

/**
 *
 * @author Doug Simon
 */
public class InvocationStubGenerator<T> {

    private static volatile int methodNameSuffix;
    private static volatile int constructorNameSuffix;
    private static volatile int serializationConstructorNameSuffix;

    static final String GEN_PACKAGE_NAME = new com.sun.max.vm.reflection.Package().name();
    static final String SERIALIZATION_CONSTRUCTOR_STUB_BASE = "GeneratedSerializationConstructorStub";
    static final String CONSTRUCTOR_STUB_BASE = "GeneratedConstructorStub";
    static final String METHOD_STUB_BASE = "GeneratedMethodStub";

    private static final String SAVE_JAVA_SOURCE_PROPERTY = "max.reflection.InvocationStubGenerator.saveSource";
    private static boolean saveJavaSource = System.getProperty(SAVE_JAVA_SOURCE_PROPERTY) != null;

    /**
     * Determines if a given class name specifies a generated stub class.
     */
    public static boolean isGeneratedStubClassName(String typeName) {
        if (typeName.startsWith(GEN_PACKAGE_NAME + ".")) {
            final String simpleClassName = typeName.substring(GEN_PACKAGE_NAME.length() + 1);
            return isSimpleGeneratedStubClassName(simpleClassName);
        }
        return false;
    }

    /**
     * Determines if a given non-qualified class name specifies a generated stub class.
     */
    private static boolean isSimpleGeneratedStubClassName(final String simpleClassName) {
        return simpleClassName.startsWith(METHOD_STUB_BASE) ||
               simpleClassName.startsWith(CONSTRUCTOR_STUB_BASE) ||
               simpleClassName.startsWith(SERIALIZATION_CONSTRUCTOR_STUB_BASE);
    }

    private static synchronized Utf8Constant generateName(boolean isConstructor, boolean forSerialization) {
        if (isConstructor) {
            if (forSerialization) {
                final int suffix = ++serializationConstructorNameSuffix;
                return SymbolTable.makeSymbol(GEN_PACKAGE_NAME + "." + SERIALIZATION_CONSTRUCTOR_STUB_BASE + suffix);
            }
            final int suffix = ++constructorNameSuffix;
            return SymbolTable.makeSymbol(GEN_PACKAGE_NAME + "." + CONSTRUCTOR_STUB_BASE + suffix);
        }
        final int suffix = ++methodNameSuffix;
        return SymbolTable.makeSymbol(GEN_PACKAGE_NAME + "." + METHOD_STUB_BASE + suffix);
    }

    private final ConstantPoolEditor constantPoolEditor;
    private final Boxing boxing;
    private final T stub;
    private final Class[] runtimeParameterTypes;
    private final boolean isStatic;
    private final boolean isPrivate;
    private final boolean isInterface;
    private final boolean isConstructor;

    // These are constant pool indexes (CPIs) for the non-shared constants used by the generated code
    private final int targetCPI;
    private final int incorrectArgumentCountMessageCPI;
    private final int[] runtimeParameterTypesPoolCPIs;
    private final int classToInstantiateCPI;
    private final int declaringClassCPI;

    static class PoolConstantArrayAppender {
        private final PoolConstant[] constants;
        private int index;

        PoolConstantArrayAppender(PoolConstant[] array, int index) {
            constants = array;
            this.index = index;
        }

        int append(PoolConstant entry) {
            final int index = this.index;
            constants[this.index++] = entry;
            return index;
        }

        public int index() {
            return index;
        }
    }

    /**
     * Creates a generator for a stub used to invoke a {@code target}.
     *
     * @param target the {@linkplain Method method} or {@linkplain Constructor constructor} for which the stub is being
     *            generated
     * @param superClass the super class of the stub. Must be {@link GeneratedMethodStub} or
     *            {@link GeneratedConstructorStub}.
     * @param name the VM-level name of the target (must be "<init>" if target is a constructor)
     * @param declaringClass the class in which the target is declared
     * @param returnType the declared return type of the target
     * @param parameterTypes the declared parameter types of the target
     * @param isStatic specifies if the target is {@code static}
     * @param isPrivate specifies if the target is {@code private}
     * @param classToInstantiate the class instantiated by the target (ignored if target is not a constructor and only non-null for serialization stubs)
     * @param boxing enum value encapsulating the semantics of how values are to be boxed and unboxed by the stub
     */
    InvocationStubGenerator(AccessibleObject target,
                    Class<T> superClass,
                    Utf8Constant name,
                    Class declaringClass,
                    Class returnType,
                    Class[] parameterTypes,
                    boolean isStatic,
                    boolean isPrivate,
                    Class classToInstantiate,
                    Boxing boxing) {
        try {
            this.boxing = boxing;
            this.isStatic = isStatic;
            this.isPrivate = isPrivate;
            this.isInterface = declaringClass.isInterface();
            this.isConstructor = target instanceof Constructor;
            this.runtimeParameterTypes = boxing.runtimeParameterTypes(parameterTypes, declaringClass, isStatic, isConstructor);
            boolean forSerialization = false;
            if (isConstructor) {
                if (classToInstantiate == null) {
                    classToInstantiate = declaringClass;
                } else {
                    forSerialization = true;
                }
            }
            final Utf8Constant stubClassName = generateName(isConstructor, forSerialization);
            final ClassActor declaringClassActor = ClassActor.fromJava(declaringClass);

            // Create the (non-shared) constant pool entries specific to this stub
            final MethodRefConstant targetMethodConstant = createMethodConstant(isInterface, ClassActor.fromJava(declaringClass), name, SignatureDescriptor.create(returnType, parameterTypes));
            final StringConstant incorrectArgumentCountMessageConstant = createStringConstant("expected " + runtimeParameterTypes.length + " arguments, received ");
            final ClassConstant[] runtimeParameterTypesPoolConstants = new ClassConstant[runtimeParameterTypes.length];
            for (int i = 0; i != runtimeParameterTypes.length; ++i) {
                final Class runtimeParameterType = runtimeParameterTypes[i];
                runtimeParameterTypesPoolConstants[i] = runtimeParameterType.isPrimitive() ? null : createClassConstant(runtimeParameterType);
            }

            // Create the array of pool constants, recording the indexes of the non-shared constants as they are
            // appended to the array
            final PoolConstant[] constants = new PoolConstant[PROTOTYPE_CONSTANTS.length + 4 + runtimeParameterTypesPoolConstants.length];
            System.arraycopy(PROTOTYPE_CONSTANTS, 0, constants, 0, PROTOTYPE_CONSTANTS.length);
            final PoolConstantArrayAppender appender = new PoolConstantArrayAppender(constants, PROTOTYPE_CONSTANTS.length);
            this.declaringClassCPI = appender.append(createClassConstant(declaringClass));
            this.targetCPI = appender.append(targetMethodConstant);
            this.incorrectArgumentCountMessageCPI = appender.append(incorrectArgumentCountMessageConstant);
            this.classToInstantiateCPI = isConstructor ? appender.append(createClassConstant(classToInstantiate)) : -1;
            this.runtimeParameterTypesPoolCPIs = new int[runtimeParameterTypesPoolConstants.length];
            for (int i = 0; i != runtimeParameterTypesPoolConstants.length; ++i) {
                final ClassConstant classConstant = runtimeParameterTypesPoolConstants[i];
                if (classConstant != null) {
                    runtimeParameterTypesPoolCPIs[i] = appender.append(classConstant);
                }
            }

            final ConstantPool constantPool = new ConstantPool(declaringClassActor.constantPool().classLoader(), constants, appender.index());
            this.constantPoolEditor = constantPool.edit();

            final ClassMethodActor initMethodActor = generateInit(superClass);
            final ClassMethodActor invokeMethodActor = generateInvoke(returnType);
            final ClassMethodActor[] classMethodActors = new ClassMethodActor[]{initMethodActor, invokeMethodActor};

            final ClassActor superClassActor = ClassActor.fromJava(superClass);
            final InterfaceActor[] interfaceActors = new InterfaceActor[0];
            final FieldActor[] fieldActors = new FieldActor[0];

            final ClassActor stubClassActor;
            synchronized (declaringClassActor.classLoader) {
                stubClassActor = ClassActorFactory.createTupleOrHybridClassActor(
                    constantPool,
                    declaringClassActor.classLoader,
                    stubClassName,
                    ClassfileReader.JAVA_1_5_VERSION,
                    (char) 0,
                    Modifier.PUBLIC | Actor.GENERATED,
                    superClassActor,
                    interfaceActors,
                    fieldActors,
                    classMethodActors,
                    Actor.NO_GENERIC_SIGNATURE,
                    Actor.NO_RUNTIME_VISIBLE_ANNOTATION_BYTES,
                    ClassActor.NO_SOURCE_FILE_NAME,
                    ClassActor.NO_INNER_CLASSES,
                    ClassActor.NO_OUTER_CLASS,
                    ClassActor.NO_ENCLOSING_METHOD_INFO);
            }

            try {
                ClassfileWriter.saveGeneratedClass(new ClassInfo(stubClassActor), constantPoolEditor.copy());
                if (MaxineVM.isHosted() && saveJavaSource) {
                    traceStubAsJavaSource(superClass, name, declaringClass, returnType, parameterTypes, isStatic, classToInstantiate, target, boxing, stubClassName);
                }
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
            this.constantPoolEditor.release();

            if (MaxineVM.isHosted()) {
                stub = superClass.cast(stubClassActor.toJava().newInstance());
            } else {
                // In the target we cannot call Class.newInstance() as it calls the constructor for the stub class by reflection
                // and ends up back here. Since the stub constructor actually does nothing important we just allocate the object.
                stub = superClass.cast(Heap.createTuple(stubClassActor.dynamicHub()));
            }
        } catch (InstantiationException e) {
            throw (InternalError) new InternalError().initCause(e);
        } catch (IllegalAccessException e) {
            throw (InternalError) new InternalError().initCause(e);
        } catch (NoClassDefFoundError e) {
            throw (InternalError) new InternalError().initCause(e);
        }
    }

    T stub() {
        return stub;
    }

    private static CodeAttribute generatedConstructorStubInitTemplate;
    private static CodeAttribute generatedMethodStubInitTemplate;

    private CodeAttribute generateInitCodeAttribute(int superConstructorCPI) {
        final ByteArrayBytecodeAssembler asm = new ByteArrayBytecodeAssembler(constantPoolEditor);
        asm.allocateLocal(Kind.REFERENCE);

        asm.aload(0);
        asm.invokespecial(superConstructorCPI, 1, 0);
        asm.vreturn();

        return new CodeAttribute(
                        constantPoolEditor.pool(),
                        asm.code(),
                        (char) asm.maxStack(),
                        (char) asm.maxLocals(),
                        CodeAttribute.NO_EXCEPTION_HANDLER_TABLE,
                        LineNumberTable.EMPTY,
                        LocalVariableTable.EMPTY,
                        null);
    }

    private ClassMethodActor generateInit(Class superClass) {
        final CodeAttribute codeAttributeTemplate;
        if (superClass == GeneratedConstructorStub.class) {
            if (generatedConstructorStubInitTemplate == null) {
                generatedConstructorStubInitTemplate = generateInitCodeAttribute(GeneratedConstructorStub_init);
            }
            codeAttributeTemplate = generatedConstructorStubInitTemplate;
        } else {
            ProgramError.check(superClass == GeneratedMethodStub.class);
            if (generatedMethodStubInitTemplate == null) {
                generatedMethodStubInitTemplate = generateInitCodeAttribute(GeneratedMethodStub_init);
            }
            codeAttributeTemplate = generatedMethodStubInitTemplate;
        }

        final CodeAttribute codeAttribute = new CodeAttribute(
                        constantPoolEditor.pool(),
                        codeAttributeTemplate.code(),
                        (char) codeAttributeTemplate.maxStack,
                        (char) codeAttributeTemplate.maxLocals,
                        CodeAttribute.NO_EXCEPTION_HANDLER_TABLE,
                        LineNumberTable.EMPTY,
                        LocalVariableTable.EMPTY,
                        null);
        return new VirtualMethodActor(
                        SymbolTable.INIT,
                        VOID_NO_ARGS,
                        Actor.ACC_PUBLIC | Actor.INITIALIZER | Actor.ACC_SYNTHETIC,
                        codeAttribute);
    }

    private ClassMethodActor generateInvoke(Class returnType) {

        final ByteArrayBytecodeAssembler asm = new ByteArrayBytecodeAssembler(constantPoolEditor);
        final Kind returnKind = Kind.fromJava(returnType);
        boolean isUnsafe = returnKind == Kind.WORD;

        // Index of parameters:
        //     invoke(Object obj, Object[] args)
        //     newInstance(Object[] args)
        //     invoke(Value... values)
        //     newInstance(Values... values)
        asm.allocateLocal(Kind.REFERENCE); // this
        final int argsParameter;
        final int objParameter;

        int illegalArgStartPC = 0;

        int argSlots = 0;
        if (isConstructor) {
            argsParameter = asm.allocateLocal(Kind.REFERENCE);
            objParameter = -1;

            // Instantiate target class before continuing
            // new <target class type>
            // dup
            asm.new_(classToInstantiateCPI);
            asm.dup();
            ++argSlots;
        } else {
            objParameter = boxing == Boxing.JAVA ? asm.allocateLocal(Kind.REFERENCE) : -1;
            argsParameter = asm.allocateLocal(Kind.REFERENCE);

            // Get target object on operand stack if necessary.

            // We need to do an explicit null check here; we won't see
            // NullPointerExceptions from the invoke bytecode, since it's
            // covered by an exception handler.
            if (!isStatic) {
                if (boxing == Boxing.JAVA) {
                    // aload obj
                    // ifnonnull <checkcast label>
                    // new <NullPointerException>
                    // dup
                    // invokespecial <NullPointerException ctor>
                    // athrow
                    // <checkcast label:>
                    // aload obj
                    // checkcast <target class's type>
                    asm.aload(objParameter);
                    final Label l = asm.newLabel();
                    asm.ifnonnull(l);
                    asm.new_(NullPointerException);
                    asm.dup();
                    asm.invokespecial(NullPointerException_init, 1, 0);
                    asm.athrow();
                    l.bind();
                    illegalArgStartPC = asm.currentAddress();
                    asm.aload(objParameter);
                    asm.checkcast(declaringClassCPI);
                    ++argSlots;
                }
            }
        }

        // Have to check length of incoming array and throw
        // IllegalArgumentException if not correct. A concession to the
        // JCK (isn't clearly specified in the spec): we allow null in the
        // case where the argument list is zero length.
        // if no-arg:
        //   aload args
        //   ifnull <success label>
        // aload args
        // arraylength
        // sipush <num parameter types>
        // if_icmpeq <success label>
        // new <IllegalArgumentException>
        // dup
        // invokespecial <IllegalArgumentException ctor>
        // athrow
        // <success label:>
        final Label successLabel = asm.newLabel();
        if (runtimeParameterTypes.length == 0) {
            asm.aload(argsParameter);
            asm.ifnull(successLabel);
        }

        asm.aload(argsParameter);
        asm.arraylength();
        asm.iconst(runtimeParameterTypes.length);
        asm.if_icmpeq(successLabel);

        // throw new IllegalArgumentException("expected %d arguments, received %d")
        asm.new_(IllegalArgumentException);
        asm.dup();
        asm.new_(StringBuilder);
        asm.dup();
        asm.ldc(incorrectArgumentCountMessageCPI);
        asm.invokespecial(StringBuilder_init_String, 2, 0);
        asm.aload(argsParameter);
        asm.arraylength();
        asm.invokevirtual(StringBuilder_append_int, 2, 1);
        asm.invokevirtual(StringBuilder_toString, 1, 1);
        asm.invokespecial(IllegalArgumentException_init_String, 2, 0);
        asm.athrow();

        // Iterate through incoming actual parameters, ensuring that each
        // is compatible with the formal parameter type, and pushing the
        // actual parameter on the operand stack (unboxing and widening if necessary).
        successLabel.bind();
        for (int i = 0; i < runtimeParameterTypes.length; i++) {
            final Class parameterType = runtimeParameterTypes[i];
            final Kind parameterKind = Kind.fromJava(parameterType);
            argSlots += parameterKind.stackSlots;
            // aload args
            // sipush <index>
            // aaload
            asm.aload(argsParameter);
            asm.iconst(i);
            asm.aaload();
            boxing.unbox(asm, parameterType, runtimeParameterTypesPoolCPIs[i]);
            if (parameterKind == Kind.WORD) {
                isUnsafe = true;
            }
        }

        final int invokeStartPC = asm.currentAddress();

        // OK, ready to perform the invocation.
        if (isConstructor) {
            asm.invokespecial(targetCPI, argSlots, 0);
        } else {
            final int returnValueSlots = returnKind.stackSlots;
            if (isStatic) {
                asm.invokestatic(targetCPI, argSlots, returnValueSlots);
            } else {
                if (isInterface) {
                    asm.invokeinterface(targetCPI, argSlots, argSlots, returnValueSlots);
                } else {
                    if (isPrivate && !MaxineVM.isHosted()) {
                        // Can't do this while bootstrapping as the Hotspot verifier will reject an invokespecial to an inaccessible method
                        asm.invokespecial(targetCPI, argSlots, returnValueSlots);
                    } else {
                        asm.invokevirtual(targetCPI, argSlots, returnValueSlots);
                    }
                }
            }
        }

        final int invokeEndPC = asm.currentAddress();
        boxing.box(asm, isConstructor, returnKind);
        asm.areturn();
        assert asm.stack() == 0;

        // We generate two exception handlers; one which is responsible
        // for catching ClassCastException and NullPointerException and
        // throwing IllegalArgumentException, and the other which catches
        // all java/lang/Throwable objects thrown from the target method
        // and wraps them in InvocationTargetExceptions.

        final int classCastHandler = asm.currentAddress();

        // ClassCast, etc. exception handler
        asm.setStack(1);
        asm.invokevirtual(Object_toString, 1, 1);
        asm.new_(IllegalArgumentException);
        asm.dup_x1();
        asm.swap();
        asm.invokespecial(IllegalArgumentException_init_String, 2, 0);
        asm.athrow();

        final int invocationTargetHandler = asm.currentAddress();

        // InvocationTargetException exception handler
        asm.setStack(1);
        asm.new_(InvocationTargetException);
        asm.dup_x1();
        asm.swap();
        asm.invokespecial(InvocationTargetException_init_Throwable, 2, 0);
        asm.athrow();

        // Generate exception table. We cover the entire code sequence
        // with an exception handler which catches ClassCastException and
        // converts it into an IllegalArgumentException.
        final Sequence<ExceptionHandlerEntry> exceptionHandlerEntries = new ArraySequence<ExceptionHandlerEntry>(
            new ExceptionHandlerEntry(illegalArgStartPC, invokeStartPC, classCastHandler, ClassCastException),
            new ExceptionHandlerEntry(illegalArgStartPC, invokeStartPC, classCastHandler, NullPointerException),
            new ExceptionHandlerEntry(invokeStartPC, invokeEndPC, invocationTargetHandler, 0)
        );

        final CodeAttribute codeAttribute = new CodeAttribute(
                        constantPoolEditor.pool(),
                        asm.code(),
                        (char) asm.maxStack(),
                        (char) asm.maxLocals(),
                        exceptionHandlerEntries,
                        LineNumberTable.EMPTY,
                        LocalVariableTable.EMPTY,
                        null);

        VirtualMethodActor virtualMethodActor;
        if (isConstructor) {
            final TypeDescriptor[] checkedExceptions = {
                JavaTypeDescriptor.INSTANTIATION_EXCEPTION,
                JavaTypeDescriptor.ILLEGAL_ARGUMENT_EXCEPTION,
                JavaTypeDescriptor.INVOCATION_TARGET_EXCEPTION
            };
            virtualMethodActor = new VirtualMethodActor(newInstance,
                boxing.newInstanceSignature(),
                Actor.ACC_PUBLIC | Actor.ACC_SYNTHETIC,
                codeAttribute);
            final ClassRegistry classRegistry = ClassRegistry.makeRegistry(constantPoolEditor.pool().classLoader());
            classRegistry.set(CHECKED_EXCEPTIONS, virtualMethodActor, checkedExceptions);
        } else {
            final TypeDescriptor[] checkedExceptions = {
                JavaTypeDescriptor.ILLEGAL_ARGUMENT_EXCEPTION,
                JavaTypeDescriptor.INVOCATION_TARGET_EXCEPTION
            };
            virtualMethodActor = new VirtualMethodActor(invoke,
                            boxing.invokeSignature(),
                            Actor.ACC_PUBLIC | Actor.ACC_SYNTHETIC,
                            codeAttribute);
            final ClassRegistry classRegistry = ClassRegistry.makeRegistry(constantPoolEditor.pool().classLoader());
            classRegistry.set(CHECKED_EXCEPTIONS, virtualMethodActor, checkedExceptions);
        }
        if (isUnsafe) {
            virtualMethodActor.beUnsafe();
        }
        return virtualMethodActor;
    }

    private void traceStubAsJavaSource(Class superClass,
                    Utf8Constant name,
                    Class declaringClass,
                    Class returnType,
                    Class[] parameterTypes,
                    boolean isStatic,
                    Class classToInstantiate,
                    AccessibleObject target,
                    Boxing boxing,
                    final Utf8Constant stubClassName) throws IOException {
        final String simpleStubClassName = stubClassName.toString().substring(GEN_PACKAGE_NAME.length() + 1);
        final IndentWriter writer = IndentWriter.traceStreamWriter();
        writer.println("package " + GEN_PACKAGE_NAME + ";");
        writer.println();
        writer.println("/**");
        writer.println(" * Automatically generated stub for: " + target + ".");
        writer.println(" */");
        writer.println("public class " + simpleStubClassName + " extends " + superClass.getSimpleName() + " {");
        writer.indent();
        generateInvokeAsSource(declaringClass, name, returnType, parameterTypes, isStatic, classToInstantiate, boxing, writer);
        writer.outdent();
        writer.println("}");
        writer.flush();
    }

    private void generateInvokeAsSource(
                    Class declaringClass,
                    Utf8Constant name,
                    Class returnType,
                    Class[] parameterTypes,
                    boolean isStatic,
                    Class classToInstantiate,
                    Boxing boxing,
                    IndentWriter writer) {
        final boolean isConstructor = classToInstantiate != null;
        final Class[] runtimeParameterTypes = boxing.runtimeParameterTypes(parameterTypes, declaringClass, isStatic, isConstructor);
        final Kind returnKind = Kind.fromJava(returnType);
        final ConstantPool pool = constantPoolEditor.pool();

        writer.println("@Override");
        writer.println(boxing.sourceDeclaration(isConstructor) + " {");
        writer.indent();

        writer.println("if (" + (parameterTypes.length == 0 ? "args != null && " : "") + "args.length != " + runtimeParameterTypes.length + ") {");
        writer.indent();
        writer.println("throw new IllegalArgumentException(\"expected " + runtimeParameterTypes.length + " arguments, received \" + args.length);");
        writer.outdent();
        writer.println("}");

        if (isConstructor) {
            writer.println("Object returnValue = new " + classToInstantiate.getName() + "(");
            writer.indent();
        } else {
            final String prefix;
            if (returnKind == Kind.VOID) {
                prefix = "";
            } else {
                prefix = "final " + returnType.getSimpleName() + " returnValue = ";
            }

            if (!isStatic) {
                if (boxing == Boxing.JAVA) {
                    writer.println("if (obj == null) {");
                    writer.indent();
                    writer.println("throw new NullPointerException();");
                    writer.outdent();
                    writer.println("}");
                    writer.println(prefix + "(" + boxing.sourceUnbox(pool, declaringClass, "obj") + ")." + name + "(");
                } else {
                    writer.println(prefix + "(" + boxing.sourceUnbox(pool, declaringClass, "args[0]") + ")." + name + "(");
                }
            } else {
                writer.println(prefix + declaringClass.getName().replace('$', '.') + "." + name + "(");
            }
            writer.indent();
        }

        final int firstNonReceiverParameter = boxing == Boxing.JAVA ? 0 : (isStatic || isConstructor ? 0 : 1);
        for (int i = firstNonReceiverParameter; i < runtimeParameterTypes.length; i++) {
            final Class parameterType = runtimeParameterTypes[i];
            final String unbox = boxing.sourceUnbox(pool, parameterType, "args[" + i + "]");
            if (i == runtimeParameterTypes.length - 1) {
                writer.println(unbox);
            } else {
                writer.println(unbox + ",");
            }
        }

        writer.outdent();
        writer.println(");");

        writer.println("return " + boxing.sourceBox(pool, isConstructor, returnKind, "returnValue") + ";");
        writer.outdent();
        writer.println("}");
        writer.flush();
    }

    // Pool constants shared by all stubs

    private static AppendableSequence<PoolConstant> prototypeConstants;

    private static int register(PoolConstant constant) {
        if (prototypeConstants == null) {
            prototypeConstants = new ArrayListSequence<PoolConstant>();
            prototypeConstants.append(InvalidConstant.VALUE);
        }
        final int index = prototypeConstants.length();
        prototypeConstants.append(constant);
        return index;
    }

    public static final Utf8Constant newInstance = SymbolTable.makeSymbol("newInstance");
    public static final Utf8Constant invoke = SymbolTable.makeSymbol("invoke");

    static final int NullPointerException = register(createClassConstant(NullPointerException.class));
    static final int ClassCastException = register(createClassConstant(ClassCastException.class));
    static final int IllegalArgumentException = register(createClassConstant(IllegalArgumentException.class));
    static final int InstantiationException = register(createClassConstant(InstantiationException.class));
    static final int InvocationTargetException = register(createClassConstant(InvocationTargetException.class));
    static final int StringBuilder = register(createClassConstant(StringBuilder.class));

    static final int GeneratedConstructorStub_init = register(createClassMethodConstant(GeneratedConstructorStub.class));
    static final int GeneratedMethodStub_init = register(createClassMethodConstant(GeneratedMethodStub.class));
    static final int NullPointerException_init = register(createClassMethodConstant(NullPointerException.class));
    static final int IllegalArgumentException_init = register(createClassMethodConstant(IllegalArgumentException.class));
    static final int IllegalArgumentException_init_String = register(createClassMethodConstant(IllegalArgumentException.class, String.class));
    static final int StringBuilder_init_String = register(createClassMethodConstant(StringBuilder.class, String.class));
    static final int InvocationTargetException_init_Throwable = register(createClassMethodConstant(InvocationTargetException.class, Throwable.class));

    static final int Object_toString = register(createClassMethodConstant(Object.class, SymbolTable.makeSymbol("toString")));

    static final int Word_asOffset = register(createClassMethodConstant(Word.class, SymbolTable.makeSymbol("asOffset")));
    static final int Word_asAddress = register(createClassMethodConstant(Word.class, SymbolTable.makeSymbol("asAddress")));
    static final int Word_asPointer = register(createClassMethodConstant(Word.class, SymbolTable.makeSymbol("asPointer")));
    static final int Word_asSize = register(createClassMethodConstant(Word.class, SymbolTable.makeSymbol("asSize")));

    static final int StringBuilder_append_int = register(createClassMethodConstant(StringBuilder.class, SymbolTable.makeSymbol("append"), int.class));
    static final int StringBuilder_append_String = register(createClassMethodConstant(StringBuilder.class, SymbolTable.makeSymbol("append"), String.class));
    static final int StringBuilder_append_Object = register(createClassMethodConstant(StringBuilder.class, SymbolTable.makeSymbol("append"), Object.class));
    static final int StringBuilder_toString = register(createClassMethodConstant(StringBuilder.class, SymbolTable.makeSymbol("toString")));

    static final int BYTES_PER_OPERAND_STACK_UNIT = 4;

    static final Map<KindEnum, Integer> JAVA_BOX_PRIMITIVE;
    static final Map<KindEnum, Integer> JAVA_UNBOX_PRIMITIVE;

    static final Map<KindEnum, Integer> VALUE_BOX;
    static final Map<KindEnum, Integer> VALUE_UNBOX;

    @HOSTED_ONLY
    static final Map<KindEnum, Method> VALUE_UNBOX_METHOD = new EnumMap<KindEnum, Method>(KindEnum.class);

    static final Map<Class<? extends Word>, Integer> CAST_WORD;

    static final int VoidValue_VOID;

    static final SignatureDescriptor VOID_NO_ARGS;

    static final PoolConstant[] PROTOTYPE_CONSTANTS;

    @HOSTED_ONLY
    public static Method findValueUnboxMethod(Kind kind) {
        Method method = VALUE_UNBOX_METHOD.get(kind.asEnum);
        if (method == null) {
            final String kindName = kind.name.toString();
            if (kind == Kind.REFERENCE) {
                method = Classes.getDeclaredMethod(Value.class, "unboxObject");
            } else {
                final String camelCaseName = Character.toUpperCase(kindName.charAt(0)) + kindName.substring(1);
                method = Classes.getDeclaredMethod(Value.class, "unbox" + camelCaseName);
            }
            VALUE_UNBOX_METHOD.put(kind.asEnum, method);
        }
        return method;
    }

    static {
        final EnumMap<KindEnum, Integer> prototype = new EnumMap<KindEnum, Integer>(KindEnum.class);
        final Map<KindEnum, Integer> javaBoxPrimitive = prototype.clone();
        final Map<KindEnum, Integer> javaUnboxPrimitive = prototype.clone();
        final Map<KindEnum, Integer> valueBox = prototype.clone();
        final Map<KindEnum, Integer> valueUnbox = prototype.clone();

        for (Kind kind : Kind.PRIMITIVE_VALUES) {
            final Class<?> boxClass = kind.boxedClass;
            final String kindName = kind.name.toString();
            javaBoxPrimitive.put(kind.asEnum, register(createClassMethodConstant(boxClass, SymbolTable.makeSymbol("valueOf"), kind.javaClass)));
            javaUnboxPrimitive.put(kind.asEnum, register(createClassMethodConstant(Kind.class, SymbolTable.makeSymbol("unbox" + Strings.capitalizeFirst(kindName, true)), Object.class)));
        }

        valueBox.put(Kind.REFERENCE.asEnum, register(createClassMethodConstant(ReferenceValue.class, SymbolTable.makeSymbol("from"), Object.class)));
        valueUnbox.put(Kind.REFERENCE.asEnum, register(createClassMethodConstant(findValueUnboxMethod(Kind.REFERENCE))));
        for (Kind kind : Kind.VALUES) {
            final Class<?> valueBoxClass = kind.valueClass;
            if (!valueUnbox.containsKey(kind.asEnum)) {
                valueUnbox.put(kind.asEnum, register(createClassMethodConstant(findValueUnboxMethod(kind))));
            }
            if (!valueBox.containsKey(kind.asEnum)) {
                valueBox.put(kind.asEnum, register(createClassMethodConstant(valueBoxClass, SymbolTable.makeSymbol("from"), kind.javaClass)));
            }
        }

        VoidValue_VOID = register(createFieldConstant(VoidValue.class, SymbolTable.makeSymbol("VOID")));

        VOID_NO_ARGS = SignatureDescriptor.fromJava(Void.TYPE);

        VALUE_BOX = Collections.unmodifiableMap(valueBox);
        VALUE_UNBOX = Collections.unmodifiableMap(valueUnbox);
        JAVA_BOX_PRIMITIVE = Collections.unmodifiableMap(javaBoxPrimitive);
        JAVA_UNBOX_PRIMITIVE = Collections.unmodifiableMap(javaUnboxPrimitive);

        Map<Class<? extends Word>, Integer> castWord = new HashMap<Class<? extends Word>, Integer>();
        castWord.put(Offset.class, Word_asOffset);
        castWord.put(Size.class, Word_asSize);
        castWord.put(Address.class, Word_asAddress);
        castWord.put(Pointer.class, Word_asPointer);
        CAST_WORD = Collections.unmodifiableMap(castWord);
    }

    /**
     * This must be the last thing executed in the static initializer.
     */
    static {
        PROTOTYPE_CONSTANTS = Sequence.Static.toArray(prototypeConstants, new PoolConstant[prototypeConstants.length()]);
    }
}
