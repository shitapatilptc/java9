/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package java.lang.module;

import java.io.File;
import java.io.FilePermission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jdk.internal.module.ModuleBootstrap;
import jdk.internal.module.ModulePath;
import jdk.internal.module.SystemModuleFinder;
import sun.security.action.GetPropertyAction;

/**
 * A finder of modules. A {@code ModuleFinder} is used to find modules during
 * <a href="package-summary.html#resolution">resolution</a> or
 * <a href="package-summary.html#servicebinding">service binding</a>.
 *
 * <p> A {@code ModuleFinder} can only find one module with a given name. A
 * {@code ModuleFinder} that finds modules in a sequence of directories, for
 * example, will locate the first occurrence of a module of a given name and
 * will ignore other modules of that name that appear in directories later in
 * the sequence. </p>
 *
 * <p> Example usage: </p>
 *
 * <pre>{@code
 *     Path dir1, dir2, dir3;
 *
 *     ModuleFinder finder = ModuleFinder.of(dir1, dir2, dir3);
 *
 *     Optional<ModuleReference> omref = finder.find("jdk.foo");
 *     omref.ifPresent(mref -> ... );
 *
 * }</pre>
 *
 * <p> The {@link #find(String) find} and {@link #findAll() findAll} methods
 * defined here can fail for several reasons. These include I/O errors, errors
 * detected parsing a module descriptor ({@code module-info.class}), or in the
 * case of {@code ModuleFinder} returned by {@link #of ModuleFinder.of}, that
 * two or more modules with the same name are found in a directory.
 * When an error is detected then these methods throw {@link FindException
 * FindException} with an appropriate {@link Throwable#getCause cause}.
 * The behavior of a {@code ModuleFinder} after a {@code FindException} is
 * thrown is undefined. For example, invoking {@code find} after an exception
 * is thrown may or may not scan the same modules that lead to the exception.
 * It is recommended that a module finder be discarded after an exception is
 * thrown. </p>
 *
 * <p> A {@code ModuleFinder} is not required to be thread safe. </p>
 *
 * @since 9
 * @spec JPMS
 */

public interface ModuleFinder {

    /**
     * Finds a reference to a module of a given name.
     *
     * <p> A {@code ModuleFinder} provides a consistent view of the
     * modules that it locates. If {@code find} is invoked several times to
     * locate the same module (by name) then it will return the same result
     * each time. If a module is located then it is guaranteed to be a member
     * of the set of modules returned by the {@link #findAll() findAll}
     * method. </p>
     *
     * @param  name
     *         The name of the module to find
     *
     * @return A reference to a module with the given name or an empty
     *         {@code Optional} if not found
     *
     * @throws FindException
     *         If an error occurs finding the module
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    Optional<ModuleReference> find(String name);

    /**
     * Returns the set of all module references that this finder can locate.
     *
     * <p> A {@code ModuleFinder} provides a consistent view of the modules
     * that it locates. If {@link #findAll() findAll} is invoked several times
     * then it will return the same (equals) result each time. For each {@code
     * ModuleReference} element in the returned set then it is guaranteed that
     * {@link #find find} will locate the {@code ModuleReference} if invoked
     * to find that module. </p>
     *
     * @apiNote This is important to have for methods such as {@link
     * Configuration#resolveAndBind resolveAndBind} that need to scan the
     * module path to find modules that provide a specific service.
     *
     * @return The set of all module references that this finder locates
     *
     * @throws FindException
     *         If an error occurs finding all modules
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    Set<ModuleReference> findAll();

    /**
     * Returns a module finder that locates the <em>system modules</em>. The
     * system modules are the modules in the Java run-time image.
     * The module finder will always find {@code java.base}.
     *
     * <p> If there is a security manager set then its {@link
     * SecurityManager#checkPermission(Permission) checkPermission} method is
     * invoked to check that the caller has been granted {@link FilePermission}
     * to recursively read the directory that is the value of the system
     * property {@code java.home}. </p>
     *
     * @return A {@code ModuleFinder} that locates the system modules
     *
     * @throws SecurityException
     *         If denied by the security manager
     */
    static ModuleFinder ofSystem() {
        String home;

        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            PrivilegedAction<String> pa = new GetPropertyAction("java.home");
            home = AccessController.doPrivileged(pa);
            Permission p = new FilePermission(home + File.separator + "-", "read");
            sm.checkPermission(p);
        } else {
            home = System.getProperty("java.home");
        }

        Path modules = Paths.get(home, "lib", "modules");
        if (Files.isRegularFile(modules)) {
            return SystemModuleFinder.getInstance();
        } else {
            Path mlib = Paths.get(home, "modules");
            if (Files.isDirectory(mlib)) {
                // exploded build may be patched
                return ModulePath.of(ModuleBootstrap.patcher(), mlib);
            } else {
                throw new InternalError("Unable to detect the run-time image");
            }
        }
    }

    /**
     * Returns a module finder that locates modules on the file system by
     * searching a sequence of directories and/or packaged modules.
     *
     * Each element in the given array is one of:
     * <ol>
     *     <li><p> A path to a directory of modules.</p></li>
     *     <li><p> A path to the <em>top-level</em> directory of an
     *         <em>exploded module</em>. </p></li>
     *     <li><p> A path to a <em>packaged module</em>. </p></li>
     * </ol>
     *
     * The module finder locates modules by searching each directory, exploded
     * module, or packaged module in array index order. It finds the first
     * occurrence of a module with a given name and ignores other modules of
     * that name that appear later in the sequence.
     *
     * <p> If an element is a path to a directory of modules then each entry in
     * the directory is a packaged module or the top-level directory of an
     * exploded module. It it an error if a directory contains more than one
     * module with the same name. If an element is a path to a directory, and
     * that directory contains a file named {@code module-info.class}, then the
     * directory is treated as an exploded module rather than a directory of
     * modules. </p>
     *
     * <p> The module finder returned by this method supports modules that are
     * packaged as JAR files. A JAR file with a {@code module-info.class} in
     * the top-level directory of the JAR file (or overridden by a versioned
     * entry in a {@link java.util.jar.JarFile#isMultiRelease() multi-release}
     * JAR file) is a modular JAR and is an <em>explicit module</em>.
     * A JAR file that does not have a {@code module-info.class} in the
     * top-level directory is created as an automatic module. The components
     * for the automatic module are derived as follows:
     *
     * <ul>
     *
     *     <li><p> The module {@link ModuleDescriptor#name() name}, and {@link
     *     ModuleDescriptor#version() version} if applicable, is derived from
     *     the file name of the JAR file as follows: </p>
     *
     *     <ul>
     *
     *         <li><p> The {@code .jar} suffix is removed. </p></li>
     *
     *         <li><p> If the name matches the regular expression {@code
     *         "-(\\d+(\\.|$))"} then the module name will be derived from the
     *         subsequence preceding the hyphen of the first occurrence. The
     *         subsequence after the hyphen is parsed as a {@link
     *         ModuleDescriptor.Version} and ignored if it cannot be parsed as
     *         a {@code Version}. </p></li>
     *
     *         <li><p> For the module name, then any trailing digits and dots
     *         are removed, all non-alphanumeric characters ({@code [^A-Za-z0-9]})
     *         are replaced with a dot ({@code "."}), all repeating dots are
     *         replaced with one dot, and all leading and trailing dots are
     *         removed. </p></li>
     *
     *         <li><p> As an example, a JAR file named {@code foo-bar.jar} will
     *         derive a module name {@code foo.bar} and no version. A JAR file
     *         named {@code foo-1.2.3-SNAPSHOT.jar} will derive a module name
     *         {@code foo} and {@code 1.2.3-SNAPSHOT} as the version. </p></li>
     *
     *     </ul></li>
     *
     *     <li><p> The set of packages in the module is derived from the
     *     non-directory entries in the JAR file that have names ending in
     *     "{@code .class}". A candidate package name is derived from the name
     *     using the characters up to, but not including, the last forward slash.
     *     All remaining forward slashes are replaced with dot ({@code "."}). If
     *     the resulting string is a legal package name then it is assumed to be
     *     a package name. For example, if the JAR file contains the entry
     *     "{@code p/q/Foo.class}" then the package name derived is
     *     "{@code p.q}".</p></li>
     *
     *     <li><p> The contents of entries starting with {@code
     *     META-INF/services/} are assumed to be service configuration files
     *     (see {@link java.util.ServiceLoader}). If the name of a file
     *     (that follows {@code META-INF/services/}) is a legal class name
     *     then it is assumed to be the fully-qualified class name of a service
     *     type. The entries in the file are assumed to be the fully-qualified
     *     class names of provider classes. </p></li>
     *
     *     <li><p> If the JAR file has a {@code Main-Class} attribute in its
     *     main manifest then its value is the module {@link
     *     ModuleDescriptor#mainClass() main class}. </p></li>
     *
     * </ul>
     *
     * <p> If a {@code ModuleDescriptor} cannot be created (by means of the
     * {@link ModuleDescriptor.Builder ModuleDescriptor.Builder} API) for an
     * automatic module then {@code FindException} is thrown. This can arise
     * when a legal module name cannot be derived from the file name of the JAR
     * file, where the JAR file contains a {@code .class} in the top-level
     * directory of the JAR file, where an entry in a service configuration
     * file is not a legal class name or its package name is not in the set of
     * packages derived for the module, or where the module main class is not
     * a legal class name or its package is not in the module. </p>
     *
     * <p> In addition to JAR files, an implementation may also support modules
     * that are packaged in other implementation specific module formats. If
     * an element in the array specified to this method is a path to a directory
     * of modules then entries in the directory that not recognized as modules
     * are ignored. If an element in the array is a path to a packaged module
     * that is not recognized then a {@code FindException} is thrown when the
     * file is encountered. Paths to files that do not exist are always ignored.
     * </p>
     *
     * <p> As with automatic modules, the contents of a packaged or exploded
     * module may need to be <em>scanned</em> in order to determine the packages
     * in the module. If a {@code .class} file (other than {@code
     * module-info.class}) is found in the top-level directory then it is
     * assumed to be a class in the unnamed package and so {@code FindException}
     * is thrown. </p>
     *
     * <p> Finders created by this method are lazy and do not eagerly check
     * that the given file paths are directories or packaged modules.
     * Consequently, the {@code find} or {@code findAll} methods will only
     * fail if invoking these methods results in searching a directory or
     * packaged module and an error is encountered. </p>
     *
     * @param entries
     *        A possibly-empty array of paths to directories of modules
     *        or paths to packaged or exploded modules
     *
     * @return A {@code ModuleFinder} that locates modules on the file system
     */
    static ModuleFinder of(Path... entries) {
        // special case zero entries
        if (entries.length == 0) {
            return new ModuleFinder() {
                @Override
                public Optional<ModuleReference> find(String name) {
                    Objects.requireNonNull(name);
                    return Optional.empty();
                }

                @Override
                public Set<ModuleReference> findAll() {
                    return Collections.emptySet();
                }
            };
        }

        return ModulePath.of(entries);
    }

    /**
     * Returns a module finder that is composed from a sequence of zero or more
     * module finders. The {@link #find(String) find} method of the resulting
     * module finder will locate a module by invoking the {@code find} method
     * of each module finder, in array index order, until either the module is
     * found or all module finders have been searched. The {@link #findAll()
     * findAll} method of the resulting module finder will return a set of
     * modules that includes all modules located by the first module finder.
     * The set of modules will include all modules located by the second or
     * subsequent module finder that are not located by previous module finders
     * in the sequence.
     *
     * <p> When locating modules then any exceptions or errors thrown by the
     * {@code find} or {@code findAll} methods of the underlying module finders
     * will be propagated to the caller of the resulting module finder's
     * {@code find} or {@code findAll} methods. </p>
     *
     * @param finders
     *        The array of module finders
     *
     * @return A {@code ModuleFinder} that composes a sequence of module finders
     */
    static ModuleFinder compose(ModuleFinder... finders) {
        // copy the list and check for nulls
        final List<ModuleFinder> finderList = List.of(finders);

        return new ModuleFinder() {
            private final Map<String, ModuleReference> nameToModule = new HashMap<>();
            private Set<ModuleReference> allModules;

            @Override
            public Optional<ModuleReference> find(String name) {
                // cached?
                ModuleReference mref = nameToModule.get(name);
                if (mref != null)
                    return Optional.of(mref);
                Optional<ModuleReference> omref = finderList.stream()
                        .map(f -> f.find(name))
                        .flatMap(Optional::stream)
                        .findFirst();
                omref.ifPresent(m -> nameToModule.put(name, m));
                return omref;
            }

            @Override
            public Set<ModuleReference> findAll() {
                if (allModules != null)
                    return allModules;
                // seed with modules already found
                Set<ModuleReference> result = new HashSet<>(nameToModule.values());
                finderList.stream()
                          .flatMap(f -> f.findAll().stream())
                          .forEach(mref -> {
                              String name = mref.descriptor().name();
                              if (nameToModule.putIfAbsent(name, mref) == null) {
                                  result.add(mref);
                              }
                          });
                allModules = Collections.unmodifiableSet(result);
                return allModules;
            }
        };
    }

}
