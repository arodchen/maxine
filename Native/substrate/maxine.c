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
/**
 * The main program of the VM.
 * Loads, verifies and mmaps the boot image,
 * hands control over to the VM's compiled code, which has been written in Java,
 * by calling a VM entry point as a C function.
 *
 * @author Bernd Mathiske
 */
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <alloca.h>

#include "debug.h"
#include "image.h"
#include "threads.h"
#include "messenger.h"
#include "os.h"
#if os_DARWIN
#include <crt_externs.h>
#endif

#include "maxine.h"

#define IMAGE_FILE_NAME  "maxine.vm"
#define DARWIN_STACK_ALIGNMENT ((Address) 16)
#define ENABLE_CARD_TABLE_VERIFICATION 0

//Size of extra space that is allocated as part of auxiliary space passed to the primordial thread.
//This space is used to record the address of all the reference fields that are written to. The recorded
//references are checked against the card table for corresponding dirty cards.
//Note : The 1 Gb space is just a guess-timate which can hold only 128 Mb of 64 bit references

#if ENABLE_CARD_TABLE_VERIFICATION
	#define REFERENCE_BUFFER_SIZE (1024*1024*1024)
#else
	#define REFERENCE_BUFFER_SIZE (0)
#endif

#if os_DARWIN
    static char *_executablePath;
#endif

static void getExecutablePath(char *result) {
#if os_DARWIN
    if (realpath(_executablePath, result) == NULL) {
        fprintf(stderr, "could not read %s\n", _executablePath);
        exit(1);
    }
    int numberOfChars = strlen(result);
#elif os_GUESTVMXEN
    result[0] = 0;
    return;
#elif os_LINUX
    char *linkName = "/proc/self/exe";
#elif os_SOLARIS
    char *linkName = "/proc/self/path/a.out";
#else
#   error getExecutablePath() not supported on other platforms yet
#endif

#if os_LINUX || os_SOLARIS
    // read the symbolic link to figure out what the executable is.
    int numberOfChars = readlink(linkName, result, MAX_PATH_LENGTH);
    if (numberOfChars < 0) {
        fprintf(stderr, "could not read %s\n", linkName);
        exit(1);
    }
#endif

#if !os_GUESTVMXEN
    char *p;
    // chop off the name of the executable
    for (p = result + numberOfChars; p >= result; p--) {
        if (*p == '/') {
            p[1] = 0;
            break;
        }
    }
#endif
}

static void getImageFilePath(char *result) {
#if !os_GUESTVMXEN
    getExecutablePath(result);

    // append the name of the image to the executable path
    strcpy(result + strlen(result), IMAGE_FILE_NAME);
#endif
}


static int loadImage(void) {
    char imageFilePath[MAX_PATH_LENGTH];
    getImageFilePath(imageFilePath);
    return image_load(imageFilePath);
}

static void *openDynamicLibrary(char *path) {
#if DEBUG_LINKER
	if (path == NULL) {
		debug_println("openDynamicLibrary (null)");
	} else {
		debug_println("openDynamicLibrary %s (0x%016lX)", path, path);
	}
#endif
	void *result = dlopen(path, RTLD_LAZY);
#if DEBUG_LINKER
	if (path == NULL) {
		debug_println("openDynamicLibrary (null) = 0x%016lX", result);
	} else {
		debug_println("openDynamicLibrary %s = 0x%016lX", path, result);
	}
#endif
	return result;
}

/**
 *  ATTENTION: this signature must match the signatures of 'com.sun.max.vm.MaxineVM.run()':
 */
typedef jint (*VMRunMethod)(Address localSpace,
							Address bootHeapRegionStart,
                            Address auxiliarySpace,
		                    void *openDynamicLibrary(char *),
							void *dlsym(void *, const char *),
                            int argc, char *argv[]);

int maxine(int argc, char *argv[], char *executablePath) {
    VMRunMethod method;
    int exitCode = 0;
    int fd;

#if os_DARWIN
    _executablePath = executablePath;
#endif

#if DEBUG_LOADER
#if !os_GUESTVMXEN
    char *ldpath = getenv("LD_LIBRARY_PATH");
    if (ldpath == NULL) {
      debug_println("LD_LIBRARY_PATH not set");
    } else {
      debug_println("LD_LIBRARY_PATH=%s", ldpath);
    }
#endif
    int i;
    debug_println("Maxine VM, argc %d, argv %lx", argc, argv);
    for (i = 0; i < argc; i++) {
    	debug_println("arg[%d]: %lx, \"%s\"", i, argv[i], argv[i]);
    }
#endif

    fd = loadImage();

    messenger_initialize();

    threads_initialize();

#if DEBUG_LOADER
    debug_println("boot image loaded");
#endif

    method = (VMRunMethod) (image_heap() + (Address) image_header()->vmRunMethodOffset);

    // Allocate the normal and the disabled VM thread local space in one chunk:
	Address vmThreadLocals = (Address) alloca(image_header()->vmThreadLocalsSize * 2);
    memset((char *) vmThreadLocals, 0, image_header()->vmThreadLocalsSize * 2);

    // Align VM thread locals to Word boundary:
    vmThreadLocals = wordAlign(vmThreadLocals);

#if DEBUG_LOADER
    debug_println("approximate stack address %p, VM thread locals allocated at: %p", &exitCode, vmThreadLocals);
#endif

	Address auxiliarySpace = 0;
	if (image_header()->auxiliarySpaceSize > 0) {
		auxiliarySpace = (Address) malloc(image_header()->auxiliarySpaceSize + REFERENCE_BUFFER_SIZE);
		if (auxiliarySpace == 0){
			debug_println("failed to allocate auxiliary space");
		}
		else {
#if DEBUG_LOADER
			debug_println("allocated %x bytes of auxiliary space at %16x\n", image_header()->auxiliarySpaceSize, auxiliarySpace);
#endif
		}

	}

	memset(auxiliarySpace, 1, image_header()->auxiliarySpaceSize + REFERENCE_BUFFER_SIZE);

#if DEBUG_LOADER
    debug_println("entering Java by calling MaxineVM::run");
#endif
    exitCode = (*method)(vmThreadLocals, image_heap(), auxiliarySpace, openDynamicLibrary, dlsym, argc, argv);

#if DEBUG_LOADER
    debug_println("start method exited with code: %d", exitCode);
#endif

    if (fd > 0) {
        int error = close(fd);
        if (error != 0) {
            debug_println("WARNING: could not close image file");
        }
    }

#if DEBUG_LOADER
    debug_println("exit code: %d", exitCode);
#endif

    return exitCode;
}

/*
 * Native support. These global natives can be called from Java to get some basic services
 * from the C language and environment.
 */

void *native_executablePath() {
	static char result[MAX_PATH_LENGTH];
	getExecutablePath(result);
	return result;
}

void native_exit(jint code) {
	exit(code);
}

void native_trap_exit(int code, void *address) {
	debug_exit(code, "MaxineVM: Trap in native code at 0x%lx\n", address);
}

void native_stack_trap_exit(int code, void *address) {
	debug_exit(code, "MaxineVM: Native code hit the stack overflow guard page at 0x%lx\n", address);
}

#if os_DARWIN
    void *native_environment() {
      void **environ = (void **)*_NSGetEnviron();
#if DEBUG_LOADER
      int i = 0;
      for (i = 0; environ[i] != NULL; i++)
	debug_println("native_environment[%d]: %s", i, environ[i]);
#endif
      return (void *)environ;
    }
#else
    extern char ** environ;
    void *native_environment() {
    	return environ;
    }
#endif
