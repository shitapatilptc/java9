/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.jdeprscan.scan;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.tools.classfile.*;
import com.sun.tools.jdeprscan.DeprData;
import com.sun.tools.jdeprscan.DeprDB;
import com.sun.tools.jdeprscan.Messages;

import static com.sun.tools.classfile.AccessFlags.*;
import static com.sun.tools.classfile.ConstantPool.*;

/**
 * An object that represents the scanning phase of deprecation usage checking.
 * Given a deprecation database, scans the targeted directory hierarchy, jar
 * file, or individual class for uses of deprecated APIs.
 */
public class Scan {
    final PrintStream out;
    final PrintStream err;
    final List<String> classPath;
    final DeprDB db;
    final boolean verbose;

    final ClassFinder finder;
    boolean errorOccurred = false;

    public Scan(PrintStream out,
                PrintStream err,
                List<String> classPath,
                DeprDB db,
                boolean verbose) {
        this.out = out;
        this.err = err;
        this.classPath = classPath;
        this.db = db;
        this.verbose = verbose;

        ClassFinder f = new ClassFinder(verbose);

        // TODO: this isn't quite right. If we've specified a release other than the current
        // one, we should instead add a reference to the symbol file for that release instead
        // of the current image. The problems are a) it's unclear how to get from a release
        // to paths that reference the symbol files, as this might be internal to the file
        // manager; and b) the symbol file includes .sig files, not class files, which ClassFile
        // might not be able to handle.
        f.addJrt();

        for (String name : classPath) {
            if (name.endsWith(".jar")) {
                f.addJar(name);
            } else {
                f.addDir(name);
            }
        }

        finder = f;
    }

    Pattern typePattern = Pattern.compile("\\[*L(.*);");

    // "flattens" an array type name to its component type
    // and a reference type "Lpkg/pkg/pkg/name;" to its base name
    // "pkg/pkg/pkg/name".
    // TODO: deal with primitive types
    String flatten(String typeName) {
        Matcher matcher = typePattern.matcher(typeName);
        if (matcher.matches()) {
            return matcher.group(1);
        } else {
            return typeName;
        }
    }

    String typeKind(ClassFile cf) {
        AccessFlags flags = cf.access_flags;
        if (flags.is(ACC_ENUM)) {
            return "enum";
        } else if (flags.is(ACC_ANNOTATION)) {
            return "@interface";
        } else if (flags.is(ACC_INTERFACE)) {
            return "interface";
        } else {
            return "class";
        }
    }

    String dep(boolean forRemoval) {
        return Messages.get(forRemoval ? "scan.dep.removal" : "scan.dep.normal");
    }

    void printType(String key, ClassFile cf, String cname, boolean r)
            throws ConstantPoolException {
        out.println(Messages.get(key, typeKind(cf), cf.getName(), cname, dep(r)));
    }

    void printMethod(String key, ClassFile cf, String cname, String mname, String rtype,
                     boolean r) throws ConstantPoolException {
        out.println(Messages.get(key, typeKind(cf), cf.getName(), cname, mname, rtype, dep(r)));
    }

    void printField(String key, ClassFile cf, String cname, String fname,
                     boolean r) throws ConstantPoolException {
        out.println(Messages.get(key, typeKind(cf), cf.getName(), cname, fname, dep(r)));
    }

    void printFieldType(String key, ClassFile cf, String cname, String fname, String type,
                     boolean r) throws ConstantPoolException {
        out.println(Messages.get(key, typeKind(cf), cf.getName(), cname, fname, type, dep(r)));
    }

    void printHasField(ClassFile cf, String fname, String type, boolean r)
            throws ConstantPoolException {
        out.println(Messages.get("scan.out.hasfield", typeKind(cf), cf.getName(), fname, type, dep(r)));
    }

    void printHasMethodParmType(ClassFile cf, String mname, String parmType, boolean r)
            throws ConstantPoolException {
        out.println(Messages.get("scan.out.methodparmtype", typeKind(cf), cf.getName(), mname, parmType, dep(r)));
    }

    void printHasMethodRetType(ClassFile cf, String mname, String retType, boolean r)
            throws ConstantPoolException {
        out.println(Messages.get("scan.out.methodrettype", typeKind(cf), cf.getName(), mname, retType, dep(r)));
    }

    void printHasOverriddenMethod(ClassFile cf, String overridden, String mname, String desc, boolean r)
            throws ConstantPoolException {
        out.println(Messages.get("scan.out.methodoverride", typeKind(cf), cf.getName(), overridden,
                                 mname, desc, dep(r)));
    }

    void errorException(Exception ex) {
        errorOccurred = true;
        err.println(Messages.get("scan.err.exception", ex.toString()));
        if (verbose) {
            ex.printStackTrace(err);
        }
    }

    void errorNoClass(String className) {
        errorOccurred = true;
        err.println(Messages.get("scan.err.noclass", className));
    }

    void errorNoFile(String fileName) {
        errorOccurred = true;
        err.println(Messages.get("scan.err.nofile", fileName));
    }

    void errorNoMethod(String className, String methodName, String desc) {
        errorOccurred = true;
        err.println(Messages.get("scan.err.nomethod", className, methodName, desc));
    }

    /**
     * Checks whether a member (method or field) is present in a class.
     * The checkMethod parameter determines whether this checks for a method
     * or for a field.
     *
     * @param targetClass the ClassFile of the class to search
     * @param targetName the method or field's name
     * @param targetDesc the methods descriptor (ignored if checkMethod is false)
     * @param checkMethod true if checking for method, false if checking for field
     * @return boolean indicating whether the member is present
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    boolean isMemberPresent(ClassFile targetClass,
                            String targetName,
                            String targetDesc,
                            boolean checkMethod)
            throws ConstantPoolException {
        if (checkMethod) {
            for (Method m : targetClass.methods) {
                String mname = m.getName(targetClass.constant_pool);
                String mdesc = targetClass.constant_pool.getUTF8Value(m.descriptor.index);
                if (targetName.equals(mname) && targetDesc.equals(mdesc)) {
                    return true;
                }
            }
        } else {
            for (Field f : targetClass.fields) {
                String fname = f.getName(targetClass.constant_pool);
                if (targetName.equals(fname)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds all interfaces from this class to the deque of interfaces.
     *
     * @param intfs the deque of interfaces
     * @param cf the ClassFile of this class
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void addInterfaces(Deque<String> intfs, ClassFile cf)
            throws ConstantPoolException {
        int count = cf.interfaces.length;
        for (int i = 0; i < count; i++) {
            intfs.addLast(cf.getInterfaceName(i));
        }
    }

    /**
     * Resolves a member by searching this class and all its superclasses and
     * implemented interfaces.
     *
     * TODO: handles a few too many cases; needs cleanup.
     *
     * TODO: refine error handling
     *
     * @param cf the ClassFile of this class
     * @param startClassName the name of the class at which to start searching
     * @param findName the member name to search for
     * @param findDesc the method descriptor to search for (ignored for fields)
     * @param resolveMethod true if resolving a method, false if resolving a field
     * @param checkStartClass true if the start class should be searched, false if
     *                        it should be skipped
     * @return the name of the class where the member resolved, or null
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    String resolveMember(
            ClassFile cf, String startClassName, String findName, String findDesc,
            boolean resolveMethod, boolean checkStartClass)
            throws ConstantPoolException {
        ClassFile startClass;

        if (cf.getName().equals(startClassName)) {
            startClass = cf;
        } else {
            startClass = finder.find(startClassName);
            if (startClass == null) {
                errorNoClass(startClassName);
                return startClassName;
            }
        }

        // follow super_class until it's 0, meaning we've reached Object
        // accumulate interfaces of superclasses as we go along

        ClassFile curClass = startClass;
        Deque<String> intfs = new ArrayDeque<>();
        while (true) {
            if ((checkStartClass || curClass != startClass) &&
                    isMemberPresent(curClass, findName, findDesc, resolveMethod)) {
                break;
            }

            if (curClass.super_class == 0) { // reached Object
                curClass = null;
                break;
            }

            String superName = curClass.getSuperclassName();
            curClass = finder.find(superName);
            if (curClass == null) {
                errorNoClass(superName);
                break;
            }
            addInterfaces(intfs, curClass);
        }

        // search interfaces: add all interfaces and superinterfaces to queue
        // search until it's empty

        if (curClass == null) {
            addInterfaces(intfs, startClass);
            while (intfs.size() > 0) {
                String intf = intfs.removeFirst();
                curClass = finder.find(intf);
                if (curClass == null) {
                    errorNoClass(intf);
                    break;
                }

                if (isMemberPresent(curClass, findName, findDesc, resolveMethod)) {
                    break;
                }

                addInterfaces(intfs, curClass);
            }
        }

        if (curClass == null) {
            if (checkStartClass) {
                errorNoMethod(startClassName, findName, findDesc);
                return startClassName;
            } else {
                // TODO: refactor this
                // checkStartClass == false means we're checking for overrides
                // so not being able to resolve a method simply means there's
                // no overriding, which isn't an error
                return null;
            }
        } else {
            String foundClassName = curClass.getName();
            return foundClassName;
        }
    }

    /**
     * Checks the superclass of this class.
     *
     * @param cf the ClassFile of this class
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void checkSuper(ClassFile cf) throws ConstantPoolException {
        String sname = cf.getSuperclassName();
        DeprData dd = db.getTypeDeprecated(sname);
        if (dd != null) {
            printType("scan.out.extends", cf, sname, dd.isForRemoval());
        }
    }

    /**
     * Checks the interfaces of this class.
     *
     * @param cf the ClassFile of this class
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void checkInterfaces(ClassFile cf) throws ConstantPoolException {
        int ni = cf.interfaces.length;
        for (int i = 0; i < ni; i++) {
            String iname = cf.getInterfaceName(i);
            DeprData dd = db.getTypeDeprecated(iname);
            if (dd != null) {
                printType("scan.out.implements", cf, iname, dd.isForRemoval());
            }
        }
    }

    /**
     * Checks Class_info entries in the constant pool.
     *
     * @param cf the ClassFile of this class
     * @param entries constant pool entries collected from this class
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void checkClasses(ClassFile cf, CPEntries entries) throws ConstantPoolException {
        for (ConstantPool.CONSTANT_Class_info ci : entries.classes) {
            String className = ci.getName();
            DeprData dd = db.getTypeDeprecated(flatten(className));
            if (dd != null) {
                printType("scan.out.usesclass", cf, className, dd.isForRemoval());
            }
        }
    }

    /**
     * Checks methods referred to from the constant pool.
     *
     * @param cf the ClassFile of this class
     * @param nti the NameAndType_info from a MethodRef or InterfaceMethodRef entry
     * @param clname the class name
     * @param msgKey message key for localization
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void checkMethodRef(ClassFile cf,
                        String clname,
                        CONSTANT_NameAndType_info nti,
                        String msgKey) throws ConstantPoolException {
        String name = nti.getName();
        String type = nti.getType();
        clname = resolveMember(cf, flatten(clname), name, type, true, true);
        DeprData dd = db.getMethodDeprecated(clname, name, type);
        if (dd != null) {
            printMethod(msgKey, cf, clname, name, type, dd.isForRemoval());
        }
    }

    /**
     * Checks fields referred to from the constant pool.
     *
     * @param cf the ClassFile of this class
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void checkFieldRef(ClassFile cf,
                       ConstantPool.CONSTANT_Fieldref_info fri) throws ConstantPoolException {
        String clname = fri.getClassName();
        CONSTANT_NameAndType_info nti = fri.getNameAndTypeInfo();
        String name = nti.getName();
        String type = nti.getType();

        clname = resolveMember(cf, flatten(clname), name, type, false, true);
        DeprData dd = db.getFieldDeprecated(clname, name);
        if (dd != null) {
            printField("scan.out.usesfield", cf, clname, name, dd.isForRemoval());
        }
    }

    /**
     * Checks the fields declared in this class.
     *
     * @param cf the ClassFile of this class
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void checkFields(ClassFile cf) throws ConstantPoolException {
        for (Field f : cf.fields) {
            String type = cf.constant_pool.getUTF8Value(f.descriptor.index);
            DeprData dd = db.getTypeDeprecated(flatten(type));
            if (dd != null) {
                printHasField(cf, f.getName(cf.constant_pool), type, dd.isForRemoval());
            }
        }
    }

    /**
     * Checks the methods declared in this class.
     *
     * @param cf the ClassFile object of this class
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void checkMethods(ClassFile cf) throws ConstantPoolException {
        for (Method m : cf.methods) {
            String mname = m.getName(cf.constant_pool);
            String desc = cf.constant_pool.getUTF8Value(m.descriptor.index);
            MethodSig sig = MethodSig.fromDesc(desc);
            DeprData dd;

            for (String parm : sig.getParameters()) {
                dd = db.getTypeDeprecated(flatten(parm));
                if (dd != null) {
                    printHasMethodParmType(cf, mname, parm, dd.isForRemoval());
                }
            }

            String ret = sig.getReturnType();
            dd = db.getTypeDeprecated(flatten(ret));
            if (dd != null) {
                printHasMethodRetType(cf, mname, ret, dd.isForRemoval());
            }

            // check overrides
            String overridden = resolveMember(cf, cf.getName(), mname, desc, true, false);
            if (overridden != null) {
                dd = db.getMethodDeprecated(overridden, mname, desc);
                if (dd != null) {
                    printHasOverriddenMethod(cf, overridden, mname, desc, dd.isForRemoval());
                }
            }
        }
    }

    /**
     * Processes a single class file.
     *
     * @param cf the ClassFile of the class
     * @throws ConstantPoolException if a constant pool entry cannot be found
     */
    void processClass(ClassFile cf) throws ConstantPoolException {
        if (verbose) {
            out.println(Messages.get("scan.process.class", cf.getName()));
        }

        CPEntries entries = CPEntries.loadFrom(cf);

        checkSuper(cf);
        checkInterfaces(cf);
        checkClasses(cf, entries);

        for (ConstantPool.CONSTANT_Methodref_info mri : entries.methodRefs) {
            String clname = mri.getClassName();
            CONSTANT_NameAndType_info nti = mri.getNameAndTypeInfo();
            checkMethodRef(cf, clname, nti, "scan.out.usesmethod");
        }

        for (ConstantPool.CONSTANT_InterfaceMethodref_info imri : entries.intfMethodRefs) {
            String clname = imri.getClassName();
            CONSTANT_NameAndType_info nti = imri.getNameAndTypeInfo();
            checkMethodRef(cf, clname, nti, "scan.out.usesintfmethod");
        }

        for (ConstantPool.CONSTANT_Fieldref_info fri : entries.fieldRefs) {
            checkFieldRef(cf, fri);
        }

        checkFields(cf);
        checkMethods(cf);
    }

    /**
     * Scans a jar file for uses of deprecated APIs.
     *
     * @param jarname the jar file to process
     * @return true on success, false on failure
     */
    public boolean scanJar(String jarname) {
        try (JarFile jf = new JarFile(jarname)) {
            out.println(Messages.get("scan.head.jar", jarname));
            finder.addJar(jarname);
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class")
                        && !name.endsWith("package-info.class")
                        && !name.endsWith("module-info.class")) {
                    processClass(ClassFile.read(jf.getInputStream(entry)));
                }
            }
            return true;
        } catch (NoSuchFileException nsfe) {
            errorNoFile(jarname);
        } catch (IOException | ConstantPoolException ex) {
            errorException(ex);
        }
        return false;
    }

    /**
     * Scans class files in the named directory hierarchy for uses of deprecated APIs.
     *
     * @param dirname the directory hierarchy to process
     * @return true on success, false on failure
     */
    public boolean scanDir(String dirname) {
        Path base = Paths.get(dirname);
        int baseCount = base.getNameCount();
        finder.addDir(dirname);
        try (Stream<Path> paths = Files.walk(Paths.get(dirname))) {
            List<Path> classes =
                paths.filter(p -> p.getNameCount() > baseCount)
                     .filter(path -> path.toString().endsWith(".class"))
                     .filter(path -> !path.toString().endsWith("package-info.class"))
                     .filter(path -> !path.toString().endsWith("module-info.class"))
                     .collect(Collectors.toList());

            out.println(Messages.get("scan.head.dir", dirname));

            for (Path p : classes) {
                processClass(ClassFile.read(p));
            }
            return true;
        } catch (IOException | ConstantPoolException ex) {
            errorException(ex);
            return false;
        }
    }

    /**
     * Scans the named class for uses of deprecated APIs.
     *
     * @param className the class to scan
     * @return true on success, false on failure
     */
    public boolean processClassName(String className) {
        try {
            ClassFile cf = finder.find(className);
            if (cf == null) {
                errorNoClass(className);
                return false;
            } else {
                processClass(cf);
                return true;
            }
        } catch (ConstantPoolException ex) {
            errorException(ex);
            return false;
        }
    }

    /**
     * Scans the named class file for uses of deprecated APIs.
     *
     * @param fileName the class file to scan
     * @return true on success, false on failure
     */
    public boolean processClassFile(String fileName) {
        Path path = Paths.get(fileName);
        try {
            ClassFile cf = ClassFile.read(path);
            processClass(cf);
            return true;
        } catch (NoSuchFileException nsfe) {
            errorNoFile(fileName);
        } catch (IOException | ConstantPoolException ex) {
            errorException(ex);
        }
        return false;
    }
}