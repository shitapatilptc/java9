/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
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

package jdk.nashorn.internal.runtime;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Modifier;
import java.lang.reflect.Module;
import java.security.CodeSource;
import java.util.Objects;
import java.util.Set;

/**
 * Responsible for loading script generated classes.
 *
 */
final class ScriptLoader extends NashornLoader {
    private static final String NASHORN_PKG_PREFIX = "jdk.nashorn.internal.";

    private volatile boolean structureAccessAdded;
    private final Context context;
    private final Module scriptModule;

    /*package-private*/ Context getContext() {
        return context;
    }

    /**
     * Constructor.
     */
    ScriptLoader(final Context context) {
        super(context.getStructLoader());
        this.context = context;

        // new scripts module, it's specific exports and read-edges
        scriptModule = createModule("jdk.scripting.nashorn.scripts");

        // specific exports from nashorn to new scripts module
        NASHORN_MODULE.addExports(OBJECTS_PKG, scriptModule);
        NASHORN_MODULE.addExports(RUNTIME_PKG, scriptModule);
        NASHORN_MODULE.addExports(RUNTIME_ARRAYS_PKG, scriptModule);
        NASHORN_MODULE.addExports(RUNTIME_LINKER_PKG, scriptModule);
        NASHORN_MODULE.addExports(SCRIPTS_PKG, scriptModule);

        // nashorn needs to read scripts module methods,fields
        NASHORN_MODULE.addReads(scriptModule);
    }

    private Module createModule(final String moduleName) {
        final Module structMod = context.getStructLoader().getModule();
        final ModuleDescriptor.Builder builder =
            ModuleDescriptor.newModule(moduleName, Set.of(Modifier.SYNTHETIC))
                    .requires("java.logging")
                    .requires(NASHORN_MODULE.getName())
                    .requires(structMod.getName())
                    .packages(Set.of(SCRIPTS_PKG));

        if (Context.javaSqlFound) {
            builder.requires("java.sql");
        }

        if (Context.javaSqlRowsetFound) {
            builder.requires("java.sql.rowset");
        }

        final ModuleDescriptor descriptor = builder.build();

        final Module mod = Context.createModuleTrusted(structMod.getLayer(), descriptor, this);
        loadModuleManipulator();
        return mod;
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        checkPackageAccess(name);
        final Class<?> cl = super.loadClass(name, resolve);
        if (!structureAccessAdded) {
            final StructureLoader structLoader = context.getStructLoader();
            if (cl.getClassLoader() == structLoader) {
                structureAccessAdded = true;
                structLoader.addModuleExport(scriptModule);
            }
        }
        return cl;
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        final ClassLoader appLoader = context.getAppLoader();

        /*
         * If the appLoader is null, don't bother side-delegating to it!
         * Bootloader has been already attempted via parent loader
         * delegation from the "loadClass" method.
         *
         * Also, make sure that we don't delegate to the app loader
         * for nashorn's own classes or nashorn generated classes!
         */
        if (appLoader == null || name.startsWith(NASHORN_PKG_PREFIX)) {
            throw new ClassNotFoundException(name);
        }

        /*
         * This split-delegation is used so that caller loader
         * based resolutions of classes would work. For example,
         * java.sql.DriverManager uses caller's class loader to
         * get Driver instances. Without this split-delegation
         * a script class evaluating DriverManager.getDrivers()
         * will not get back any JDBC driver!
         */
        return appLoader.loadClass(name);
    }

    // package-private and private stuff below this point

    /**
     * Install a class for use by the Nashorn runtime
     *
     * @param name Binary name of class.
     * @param data Class data bytes.
     * @param cs CodeSource code source of the class bytes.
     *
     * @return Installed class.
     */
    synchronized Class<?> installClass(final String name, final byte[] data, final CodeSource cs) {
        return defineClass(name, data, 0, data.length, Objects.requireNonNull(cs));
    }
}
