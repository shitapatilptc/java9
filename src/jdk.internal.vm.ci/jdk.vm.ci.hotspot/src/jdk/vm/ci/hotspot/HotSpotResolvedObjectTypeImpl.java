/*
 * Copyright (c) 2011, 2017, Oracle and/or its affiliates. All rights reserved.
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
 */
package jdk.vm.ci.hotspot;

import static java.util.Objects.requireNonNull;
import static jdk.vm.ci.hotspot.CompilerToVM.compilerToVM;
import static jdk.vm.ci.hotspot.HotSpotConstantPool.isSignaturePolymorphicHolder;
import static jdk.vm.ci.hotspot.HotSpotJVMCIRuntime.runtime;
import static jdk.vm.ci.hotspot.HotSpotModifiers.jvmClassModifiers;
import static jdk.vm.ci.hotspot.HotSpotVMConfig.config;
import static jdk.vm.ci.hotspot.UnsafeAccess.UNSAFE;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteOrder;
import java.util.HashMap;

import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.meta.Assumptions.AssumptionResult;
import jdk.vm.ci.meta.Assumptions.ConcreteMethod;
import jdk.vm.ci.meta.Assumptions.ConcreteSubtype;
import jdk.vm.ci.meta.Assumptions.LeafType;
import jdk.vm.ci.meta.Assumptions.NoFinalizableSubclass;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Implementation of {@link JavaType} for resolved non-primitive HotSpot classes.
 */
final class HotSpotResolvedObjectTypeImpl extends HotSpotResolvedJavaType implements HotSpotResolvedObjectType, MetaspaceWrapperObject {

    private static final HotSpotResolvedJavaField[] NO_FIELDS = new HotSpotResolvedJavaField[0];
    private static final int METHOD_CACHE_ARRAY_CAPACITY = 8;

    /**
     * The Java class this type represents.
     */
    private final Class<?> javaClass;
    private HotSpotResolvedJavaMethodImpl[] methodCacheArray;
    private HashMap<Long, HotSpotResolvedJavaMethodImpl> methodCacheHashMap;
    private HotSpotResolvedJavaField[] instanceFields;
    private HotSpotResolvedObjectTypeImpl[] interfaces;
    private HotSpotConstantPool constantPool;
    final HotSpotJVMCIMetaAccessContext context;
    private HotSpotResolvedObjectType arrayOfType;

    /**
     * Gets the JVMCI mirror for a {@link Class} object.
     *
     * @return the {@link HotSpotResolvedJavaType} corresponding to {@code javaClass}
     */
    static HotSpotResolvedObjectTypeImpl fromObjectClass(Class<?> javaClass) {
        return (HotSpotResolvedObjectTypeImpl) runtime().fromClass(javaClass);
    }

    /**
     * Gets the JVMCI mirror from a HotSpot type. Since {@link Class} is already a proxy for the
     * underlying Klass*, it is used instead of the raw Klass*.
     *
     * Called from the VM.
     *
     * @param javaClass a {@link Class} object
     * @return the {@link ResolvedJavaType} corresponding to {@code javaClass}
     */
    @SuppressWarnings("unused")
    private static HotSpotResolvedObjectTypeImpl fromMetaspace(Class<?> javaClass) {
        return fromObjectClass(javaClass);
    }

    /**
     * Creates the JVMCI mirror for a {@link Class} object.
     *
     * <p>
     * <b>NOTE</b>: Creating an instance of this class does not install the mirror for the
     * {@link Class} type. Use {@link #fromObjectClass(Class)} or {@link #fromMetaspace(Class)}
     * instead.
     * </p>
     *
     * @param javaClass the Class to create the mirror for
     * @param context
     */
    HotSpotResolvedObjectTypeImpl(Class<?> javaClass, HotSpotJVMCIMetaAccessContext context) {
        super(getSignatureName(javaClass));
        this.javaClass = javaClass;
        this.context = context;
        assert getName().charAt(0) != '[' || isArray() : getName();
    }

    /**
     * Returns the name of this type as it would appear in a signature.
     */
    private static String getSignatureName(Class<?> javaClass) {
        if (javaClass.isArray()) {
            return javaClass.getName().replace('.', '/');
        }
        return "L" + javaClass.getName().replace('.', '/') + ";";
    }

    /**
     * Gets the metaspace Klass for this type.
     */
    long getMetaspaceKlass() {
        if (HotSpotJVMCIRuntime.getHostWordKind() == JavaKind.Long) {
            return UNSAFE.getLong(javaClass, config().klassOffset);
        }
        return UNSAFE.getInt(javaClass, config().klassOffset) & 0xFFFFFFFFL;
    }

    @Override
    public long getMetaspacePointer() {
        return getMetaspaceKlass();
    }

    /**
     * The Klass* for this object is kept alive by the direct reference to {@link #javaClass} so no
     * extra work is required.
     */
    @Override
    public boolean isRegistered() {
        return true;
    }

    @Override
    public int getModifiers() {
        if (isArray()) {
            return (getElementalType().getModifiers() & (Modifier.PUBLIC | Modifier.PRIVATE | Modifier.PROTECTED)) | Modifier.FINAL | Modifier.ABSTRACT;
        } else {
            return getAccessFlags() & jvmClassModifiers();
        }
    }

    public int getAccessFlags() {
        HotSpotVMConfig config = config();
        return UNSAFE.getInt(getMetaspaceKlass() + config.klassAccessFlagsOffset);
    }

    @Override
    public HotSpotResolvedObjectType getArrayClass() {
        if (arrayOfType == null) {
            arrayOfType = fromObjectClass(Array.newInstance(mirror(), 0).getClass());
        }
        return arrayOfType;
    }

    @Override
    public ResolvedJavaType getComponentType() {
        Class<?> javaComponentType = mirror().getComponentType();
        return javaComponentType == null ? null : runtime().fromClass(javaComponentType);
    }

    @Override
    public AssumptionResult<ResolvedJavaType> findLeafConcreteSubtype() {
        if (isLeaf()) {
            // No assumptions are required.
            return new AssumptionResult<>(this);
        }
        HotSpotVMConfig config = config();
        if (isArray()) {
            ResolvedJavaType elementalType = getElementalType();
            AssumptionResult<ResolvedJavaType> elementType = elementalType.findLeafConcreteSubtype();
            if (elementType != null && elementType.getResult().equals(elementalType)) {
                /*
                 * If the elementType is leaf then the array is leaf under the same assumptions but
                 * only if the element type is exactly the leaf type. The element type can be
                 * abstract even if there is only one implementor of the abstract type.
                 */
                AssumptionResult<ResolvedJavaType> result = new AssumptionResult<>(this);
                result.add(elementType);
                return result;
            }
            return null;
        } else if (isInterface()) {
            HotSpotResolvedObjectTypeImpl implementor = getSingleImplementor();
            /*
             * If the implementor field contains itself that indicates that the interface has more
             * than one implementors (see: InstanceKlass::add_implementor).
             */
            if (implementor == null || implementor.equals(this)) {
                return null;
            }

            assert !implementor.isInterface();
            if (implementor.isAbstract() || !implementor.isLeafClass()) {
                AssumptionResult<ResolvedJavaType> leafConcreteSubtype = implementor.findLeafConcreteSubtype();
                if (leafConcreteSubtype != null) {
                    assert !leafConcreteSubtype.getResult().equals(implementor);
                    AssumptionResult<ResolvedJavaType> newResult = new AssumptionResult<>(leafConcreteSubtype.getResult(), new ConcreteSubtype(this, implementor));
                    // Accumulate leaf assumptions and return the combined result.
                    newResult.add(leafConcreteSubtype);
                    return newResult;
                }
                return null;
            }
            return concreteSubtype(implementor);
        } else {
            HotSpotResolvedObjectTypeImpl type = this;
            while (type.isAbstract()) {
                HotSpotResolvedObjectTypeImpl subklass = type.getSubklass();
                if (subklass == null || UNSAFE.getAddress(subklass.getMetaspaceKlass() + config.nextSiblingOffset) != 0) {
                    return null;
                }
                type = subklass;
            }
            if (type.isAbstract() || type.isInterface() || !type.isLeafClass()) {
                return null;
            }
            if (this.isAbstract()) {
                return concreteSubtype(type);
            } else {
                assert this.equals(type);
                return new AssumptionResult<>(type, new LeafType(type));
            }
        }
    }

    private AssumptionResult<ResolvedJavaType> concreteSubtype(HotSpotResolvedObjectTypeImpl type) {
        if (type.isLeaf()) {
            return new AssumptionResult<>(type, new ConcreteSubtype(this, type));
        } else {
            return new AssumptionResult<>(type, new LeafType(type), new ConcreteSubtype(this, type));
        }
    }

    /**
     * Returns if type {@code type} is a leaf class. This is the case if the
     * {@code Klass::_subklass} field of the underlying class is zero.
     *
     * @return true if the type is a leaf class
     */
    private boolean isLeafClass() {
        return UNSAFE.getLong(this.getMetaspaceKlass() + config().subklassOffset) == 0;
    }

    /**
     * Returns the {@code Klass::_subklass} field of the underlying metaspace klass for the given
     * type {@code type}.
     *
     * @return value of the subklass field as metaspace klass pointer
     */
    private HotSpotResolvedObjectTypeImpl getSubklass() {
        return compilerToVM().getResolvedJavaType(this, config().subklassOffset, false);
    }

    @Override
    public HotSpotResolvedObjectTypeImpl getSuperclass() {
        Class<?> javaSuperclass = mirror().getSuperclass();
        return javaSuperclass == null ? null : fromObjectClass(javaSuperclass);
    }

    @Override
    public HotSpotResolvedObjectTypeImpl[] getInterfaces() {
        if (interfaces == null) {
            Class<?>[] javaInterfaces = mirror().getInterfaces();
            HotSpotResolvedObjectTypeImpl[] result = new HotSpotResolvedObjectTypeImpl[javaInterfaces.length];
            for (int i = 0; i < javaInterfaces.length; i++) {
                result[i] = fromObjectClass(javaInterfaces[i]);
            }
            interfaces = result;
        }
        return interfaces;
    }

    @Override
    public HotSpotResolvedObjectTypeImpl getSingleImplementor() {
        if (!isInterface()) {
            throw new JVMCIError("Cannot call getSingleImplementor() on a non-interface type: %s", this);
        }
        return compilerToVM().getImplementor(this);
    }

    public HotSpotResolvedObjectTypeImpl getSupertype() {
        if (isArray()) {
            ResolvedJavaType componentType = getComponentType();
            if (mirror() == Object[].class || componentType.isPrimitive()) {
                return fromObjectClass(Object.class);
            }
            return (HotSpotResolvedObjectTypeImpl) ((HotSpotResolvedObjectTypeImpl) componentType).getSupertype().getArrayClass();
        }
        if (isInterface()) {
            return fromObjectClass(Object.class);
        }
        return getSuperclass();
    }

    @Override
    public HotSpotResolvedObjectType findLeastCommonAncestor(ResolvedJavaType otherType) {
        if (otherType.isPrimitive()) {
            return null;
        } else {
            HotSpotResolvedObjectTypeImpl t1 = this;
            HotSpotResolvedObjectTypeImpl t2 = (HotSpotResolvedObjectTypeImpl) otherType;
            while (true) {
                if (t1.isAssignableFrom(t2)) {
                    return t1;
                }
                if (t2.isAssignableFrom(t1)) {
                    return t2;
                }
                t1 = t1.getSupertype();
                t2 = t2.getSupertype();
            }
        }
    }

    @Override
    public AssumptionResult<Boolean> hasFinalizableSubclass() {
        assert !isArray();
        if (!compilerToVM().hasFinalizableSubclass(this)) {
            return new AssumptionResult<>(false, new NoFinalizableSubclass(this));
        }
        return new AssumptionResult<>(true);
    }

    @Override
    public boolean hasFinalizer() {
        return (getAccessFlags() & config().jvmAccHasFinalizer) != 0;
    }

    @Override
    public boolean isPrimitive() {
        return false;
    }

    @Override
    public boolean isArray() {
        return mirror().isArray();
    }

    @Override
    public boolean isInitialized() {
        return isArray() ? true : getInitState() == config().instanceKlassStateFullyInitialized;
    }

    @Override
    public boolean isLinked() {
        return isArray() ? true : getInitState() >= config().instanceKlassStateLinked;
    }

    /**
     * Returns the value of the state field {@code InstanceKlass::_init_state} of the metaspace
     * klass.
     *
     * @return state field value of this type
     */
    private int getInitState() {
        assert !isArray() : "_init_state only exists in InstanceKlass";
        return UNSAFE.getByte(getMetaspaceKlass() + config().instanceKlassInitStateOffset) & 0xFF;
    }

    @Override
    public void initialize() {
        if (!isInitialized()) {
            UNSAFE.ensureClassInitialized(mirror());
            assert isInitialized();
        }
    }

    @Override
    public boolean isInstance(JavaConstant obj) {
        if (obj.getJavaKind() == JavaKind.Object && !obj.isNull()) {
            return mirror().isInstance(((HotSpotObjectConstantImpl) obj).object());
        }
        return false;
    }

    @Override
    public boolean isInstanceClass() {
        return !isArray() && !isInterface();
    }

    @Override
    public boolean isInterface() {
        return mirror().isInterface();
    }

    @Override
    public boolean isAssignableFrom(ResolvedJavaType other) {
        assert other != null;
        if (other instanceof HotSpotResolvedObjectTypeImpl) {
            HotSpotResolvedObjectTypeImpl otherType = (HotSpotResolvedObjectTypeImpl) other;
            return mirror().isAssignableFrom(otherType.mirror());
        }
        return false;
    }

    @Override
    public boolean isJavaLangObject() {
        return javaClass.equals(Object.class);
    }

    @Override
    public JavaKind getJavaKind() {
        return JavaKind.Object;
    }

    @Override
    public ResolvedJavaMethod resolveMethod(ResolvedJavaMethod method, ResolvedJavaType callerType) {
        assert !callerType.isArray();
        if (isInterface()) {
            // Methods can only be resolved against concrete types
            return null;
        }
        if (method.isConcrete() && method.getDeclaringClass().equals(this) && method.isPublic() && !isSignaturePolymorphicHolder(method.getDeclaringClass())) {
            return method;
        }
        if (!method.getDeclaringClass().isAssignableFrom(this)) {
            return null;
        }
        HotSpotResolvedJavaMethodImpl hotSpotMethod = (HotSpotResolvedJavaMethodImpl) method;
        HotSpotResolvedObjectTypeImpl hotSpotCallerType = (HotSpotResolvedObjectTypeImpl) callerType;
        return compilerToVM().resolveMethod(this, hotSpotMethod, hotSpotCallerType);
    }

    public HotSpotConstantPool getConstantPool() {
        if (constantPool == null || !isArray() && UNSAFE.getAddress(getMetaspaceKlass() + config().instanceKlassConstantsOffset) != constantPool.getMetaspaceConstantPool()) {
            /*
             * If the pointer to the ConstantPool has changed since this was last read refresh the
             * HotSpotConstantPool wrapper object. This ensures that uses of the constant pool are
             * operating on the latest one and that HotSpotResolvedJavaMethodImpls will be able to
             * use the shared copy instead of creating their own instance.
             */
            constantPool = compilerToVM().getConstantPool(this);
        }
        return constantPool;
    }

    /**
     * Gets the instance size of this type. If an instance of this type cannot be fast path
     * allocated, then the returned value is negative (its absolute value gives the size). Must not
     * be called if this is an array or interface type.
     */
    public int instanceSize() {
        assert !isArray();
        assert !isInterface();

        HotSpotVMConfig config = config();
        final int layoutHelper = layoutHelper();
        assert layoutHelper > config.klassLayoutHelperNeutralValue : "must be instance";

        // See: Klass::layout_helper_size_in_bytes
        int size = layoutHelper & ~config.klassLayoutHelperInstanceSlowPathBit;

        // See: Klass::layout_helper_needs_slow_path
        boolean needsSlowPath = (layoutHelper & config.klassLayoutHelperInstanceSlowPathBit) != 0;

        return needsSlowPath ? -size : size;
    }

    public int layoutHelper() {
        HotSpotVMConfig config = config();
        return UNSAFE.getInt(getMetaspaceKlass() + config.klassLayoutHelperOffset);
    }

    @Override
    public long getFingerprint() {
        return compilerToVM().getFingerprint(getMetaspaceKlass());
    }

    synchronized HotSpotResolvedJavaMethod createMethod(long metaspaceMethod) {
        // Maintain cache as array.
        if (methodCacheArray == null) {
            methodCacheArray = new HotSpotResolvedJavaMethodImpl[METHOD_CACHE_ARRAY_CAPACITY];
        }

        int i = 0;
        for (; i < methodCacheArray.length; ++i) {
            HotSpotResolvedJavaMethodImpl curMethod = methodCacheArray[i];
            if (curMethod == null) {
                HotSpotResolvedJavaMethodImpl newMethod = new HotSpotResolvedJavaMethodImpl(this, metaspaceMethod);
                methodCacheArray[i] = newMethod;
                context.add(newMethod);
                return newMethod;
            } else if (curMethod.getMetaspacePointer() == metaspaceMethod) {
                return curMethod;
            }
        }

        // Fall-back to hash table.
        if (methodCacheHashMap == null) {
            methodCacheHashMap = new HashMap<>();
        }

        HotSpotResolvedJavaMethodImpl lookupResult = methodCacheHashMap.get(metaspaceMethod);
        if (lookupResult == null) {
            HotSpotResolvedJavaMethodImpl newMethod = new HotSpotResolvedJavaMethodImpl(this, metaspaceMethod);
            methodCacheHashMap.put(metaspaceMethod, newMethod);
            context.add(lookupResult);
            return newMethod;
        } else {
            return lookupResult;
        }
    }

    public int getVtableLength() {
        HotSpotVMConfig config = config();
        if (isInterface() || isArray()) {
            /* Everything has the core vtable of java.lang.Object */
            return config.baseVtableLength();
        }
        int result = UNSAFE.getInt(getMetaspaceKlass() + config.klassVtableLengthOffset) / (config.vtableEntrySize / config.heapWordSize);
        assert result >= config.baseVtableLength() : UNSAFE.getInt(getMetaspaceKlass() + config.klassVtableLengthOffset) + " " + config.vtableEntrySize;
        return result;
    }

    synchronized HotSpotResolvedJavaField createField(JavaType type, long offset, int rawFlags, int index) {
        return new HotSpotResolvedJavaFieldImpl(this, type, offset, rawFlags, index);
    }

    @Override
    public AssumptionResult<ResolvedJavaMethod> findUniqueConcreteMethod(ResolvedJavaMethod method) {
        HotSpotResolvedJavaMethod hmethod = (HotSpotResolvedJavaMethod) method;
        HotSpotResolvedObjectType declaredHolder = hmethod.getDeclaringClass();
        /*
         * Sometimes the receiver type in the graph hasn't stabilized to a subtype of declared
         * holder, usually because of phis, so make sure that the type is related to the declared
         * type before using it for lookup. Unlinked types should also be ignored because we can't
         * resolve the proper method to invoke. Generally unlinked types in invokes should result in
         * a deopt instead since they can't really be used if they aren't linked yet.
         */
        if (!declaredHolder.isAssignableFrom(this) || this.isArray() || this.equals(declaredHolder) || !isLinked() || isInterface()) {
            ResolvedJavaMethod result = hmethod.uniqueConcreteMethod(declaredHolder);
            if (result != null) {
                return new AssumptionResult<>(result, new ConcreteMethod(method, declaredHolder, result));
            }
            return null;
        }
        /*
         * The holder may be a subtype of the declaredHolder so make sure to resolve the method to
         * the correct method for the subtype.
         */
        HotSpotResolvedJavaMethod resolvedMethod = (HotSpotResolvedJavaMethod) resolveMethod(hmethod, this);
        if (resolvedMethod == null) {
            // The type isn't known to implement the method.
            return null;
        }

        ResolvedJavaMethod result = resolvedMethod.uniqueConcreteMethod(this);
        if (result != null) {
            return new AssumptionResult<>(result, new ConcreteMethod(method, this, result));
        }
        return null;
    }

    FieldInfo createFieldInfo(int index) {
        return new FieldInfo(index);
    }

    /**
     * This class represents the field information for one field contained in the fields array of an
     * {@code InstanceKlass}. The implementation is similar to the native {@code FieldInfo} class.
     */
    class FieldInfo {
        /**
         * Native pointer into the array of Java shorts.
         */
        private final long metaspaceData;

        /**
         * Creates a field info for the field in the fields array at index {@code index}.
         *
         * @param index index to the fields array
         */
        FieldInfo(int index) {
            HotSpotVMConfig config = config();
            // Get Klass::_fields
            final long metaspaceFields = UNSAFE.getAddress(getMetaspaceKlass() + config.instanceKlassFieldsOffset);
            assert config.fieldInfoFieldSlots == 6 : "revisit the field parsing code";
            int offset = config.fieldInfoFieldSlots * Short.BYTES * index;
            metaspaceData = metaspaceFields + config.arrayU2DataOffset + offset;
        }

        private int getAccessFlags() {
            return readFieldSlot(config().fieldInfoAccessFlagsOffset);
        }

        private int getNameIndex() {
            return readFieldSlot(config().fieldInfoNameIndexOffset);
        }

        private int getSignatureIndex() {
            return readFieldSlot(config().fieldInfoSignatureIndexOffset);
        }

        public int getOffset() {
            HotSpotVMConfig config = config();
            final int lowPacked = readFieldSlot(config.fieldInfoLowPackedOffset);
            final int highPacked = readFieldSlot(config.fieldInfoHighPackedOffset);
            final int offset = ((highPacked << Short.SIZE) | lowPacked) >> config.fieldInfoTagSize;
            return offset;
        }

        /**
         * Helper method to read an entry (slot) from the field array. Currently field info is laid
         * on top an array of Java shorts.
         */
        private int readFieldSlot(int index) {
            int offset = Short.BYTES * index;
            return UNSAFE.getChar(metaspaceData + offset);
        }

        /**
         * Returns the name of this field as a {@link String}. If the field is an internal field the
         * name index is pointing into the vmSymbols table.
         */
        public String getName() {
            final int nameIndex = getNameIndex();
            return isInternal() ? config().symbolAt(nameIndex) : getConstantPool().lookupUtf8(nameIndex);
        }

        /**
         * Returns the signature of this field as {@link String}. If the field is an internal field
         * the signature index is pointing into the vmSymbols table.
         */
        public String getSignature() {
            final int signatureIndex = getSignatureIndex();
            return isInternal() ? config().symbolAt(signatureIndex) : getConstantPool().lookupUtf8(signatureIndex);
        }

        public JavaType getType() {
            String signature = getSignature();
            return runtime().lookupType(signature, HotSpotResolvedObjectTypeImpl.this, false);
        }

        private boolean isInternal() {
            return (getAccessFlags() & config().jvmAccFieldInternal) != 0;
        }

        public boolean isStatic() {
            return Modifier.isStatic(getAccessFlags());
        }

        public boolean hasGenericSignature() {
            return (getAccessFlags() & config().jvmAccFieldHasGenericSignature) != 0;
        }
    }

    @Override
    public ResolvedJavaField[] getInstanceFields(boolean includeSuperclasses) {
        if (instanceFields == null) {
            if (isArray() || isInterface()) {
                instanceFields = NO_FIELDS;
            } else {
                HotSpotResolvedJavaField[] prepend = NO_FIELDS;
                if (getSuperclass() != null) {
                    prepend = (HotSpotResolvedJavaField[]) getSuperclass().getInstanceFields(true);
                }
                instanceFields = getFields(false, prepend);
            }
        }
        if (!includeSuperclasses && getSuperclass() != null) {
            int superClassFieldCount = getSuperclass().getInstanceFields(true).length;
            if (superClassFieldCount == instanceFields.length) {
                // This class does not have any instance fields of its own.
                return NO_FIELDS;
            } else if (superClassFieldCount != 0) {
                HotSpotResolvedJavaField[] result = new HotSpotResolvedJavaField[instanceFields.length - superClassFieldCount];
                System.arraycopy(instanceFields, superClassFieldCount, result, 0, result.length);
                return result;
            } else {
                // The super classes of this class do not have any instance fields.
            }
        }
        return instanceFields;
    }

    @Override
    public ResolvedJavaField[] getStaticFields() {
        if (isArray()) {
            return new HotSpotResolvedJavaField[0];
        } else {
            return getFields(true, NO_FIELDS);
        }
    }

    /**
     * Gets the instance or static fields of this class.
     *
     * @param retrieveStaticFields specifies whether to return instance or static fields
     * @param prepend an array to be prepended to the returned result
     */
    private HotSpotResolvedJavaField[] getFields(boolean retrieveStaticFields, HotSpotResolvedJavaField[] prepend) {
        HotSpotVMConfig config = config();
        final long metaspaceFields = UNSAFE.getAddress(getMetaspaceKlass() + config.instanceKlassFieldsOffset);
        int metaspaceFieldsLength = UNSAFE.getInt(metaspaceFields + config.arrayU1LengthOffset);
        int resultCount = 0;
        int index = 0;
        for (int i = 0; i < metaspaceFieldsLength; i += config.fieldInfoFieldSlots, index++) {
            FieldInfo field = new FieldInfo(index);
            if (field.hasGenericSignature()) {
                metaspaceFieldsLength--;
            }

            if (field.isStatic() == retrieveStaticFields) {
                resultCount++;
            }
        }

        if (resultCount == 0) {
            return prepend;
        }

        int prependLength = prepend.length;
        resultCount += prependLength;

        HotSpotResolvedJavaField[] result = new HotSpotResolvedJavaField[resultCount];
        if (prependLength != 0) {
            System.arraycopy(prepend, 0, result, 0, prependLength);
        }

        int resultIndex = prependLength;
        for (int i = 0; i < index; ++i) {
            FieldInfo field = new FieldInfo(i);
            if (field.isStatic() == retrieveStaticFields) {
                int offset = field.getOffset();
                HotSpotResolvedJavaField resolvedJavaField = createField(field.getType(), offset, field.getAccessFlags(), i);

                // Make sure the result is sorted by offset.
                int j;
                for (j = resultIndex - 1; j >= prependLength && result[j].offset() > offset; j--) {
                    result[j + 1] = result[j];
                }
                result[j + 1] = resolvedJavaField;
                resultIndex++;
            }
        }

        return result;
    }

    @Override
    public Class<?> mirror() {
        return javaClass;
    }

    @Override
    public String getSourceFileName() {
        HotSpotVMConfig config = config();
        final int sourceFileNameIndex = UNSAFE.getChar(getMetaspaceKlass() + config.instanceKlassSourceFileNameIndexOffset);
        if (sourceFileNameIndex == 0) {
            return null;
        }
        return getConstantPool().lookupUtf8(sourceFileNameIndex);
    }

    @Override
    public Annotation[] getAnnotations() {
        return mirror().getAnnotations();
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return mirror().getDeclaredAnnotations();
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        return mirror().getAnnotation(annotationClass);
    }

    /**
     * Performs a fast-path check that this type is resolved in the context of a given accessing
     * class. A negative result does not mean this type is not resolved with respect to
     * {@code accessingClass}. That can only be determined by
     * {@linkplain HotSpotJVMCIRuntime#lookupType(String, HotSpotResolvedObjectType, boolean)
     * re-resolving} the type.
     */
    public boolean isDefinitelyResolvedWithRespectTo(ResolvedJavaType accessingClass) {
        assert accessingClass != null;
        ResolvedJavaType elementType = getElementalType();
        if (elementType.isPrimitive()) {
            // Primitive type resolution is context free.
            return true;
        }
        if (elementType.getName().startsWith("Ljava/")) {
            // Classes in a java.* package can only be defined by the
            // boot class loader. This is enforced by ClassLoader.preDefineClass()
            assert mirror().getClassLoader() == null;
            return true;
        }
        ClassLoader thisCl = mirror().getClassLoader();
        ClassLoader accessingClassCl = ((HotSpotResolvedObjectTypeImpl) accessingClass).mirror().getClassLoader();
        return thisCl == accessingClassCl;
    }

    @Override
    public ResolvedJavaType resolve(ResolvedJavaType accessingClass) {
        if (isDefinitelyResolvedWithRespectTo(requireNonNull(accessingClass))) {
            return this;
        }
        HotSpotResolvedObjectTypeImpl accessingType = (HotSpotResolvedObjectTypeImpl) accessingClass;
        return (ResolvedJavaType) runtime().lookupType(getName(), accessingType, true);
    }

    /**
     * Gets the metaspace Klass boxed in a {@link JavaConstant}.
     */
    public Constant klass() {
        return HotSpotMetaspaceConstantImpl.forMetaspaceObject(this, false);
    }

    public boolean isPrimaryType() {
        return config().secondarySuperCacheOffset != superCheckOffset();
    }

    public int superCheckOffset() {
        HotSpotVMConfig config = config();
        return UNSAFE.getInt(getMetaspaceKlass() + config.superCheckOffsetOffset);
    }

    public long prototypeMarkWord() {
        HotSpotVMConfig config = config();
        if (isArray()) {
            return config.arrayPrototypeMarkWord();
        } else {
            return UNSAFE.getAddress(getMetaspaceKlass() + config.prototypeMarkWordOffset);
        }
    }

    @Override
    public ResolvedJavaField findInstanceFieldWithOffset(long offset, JavaKind expectedEntryKind) {
        ResolvedJavaField[] declaredFields = getInstanceFields(true);
        for (ResolvedJavaField field : declaredFields) {
            HotSpotResolvedJavaField resolvedField = (HotSpotResolvedJavaField) field;
            long resolvedFieldOffset = resolvedField.offset();
            // @formatter:off
            if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN  &&
                            expectedEntryKind.isPrimitive() &&
                            !expectedEntryKind.equals(JavaKind.Void) &&
                            resolvedField.getJavaKind().isPrimitive()) {
                resolvedFieldOffset +=
                                resolvedField.getJavaKind().getByteCount() -
                                Math.min(resolvedField.getJavaKind().getByteCount(), 4 + expectedEntryKind.getByteCount());
            }
            if (resolvedFieldOffset == offset) {
                return field;
            }
            // @formatter:on
        }
        return null;
    }

    @Override
    public boolean isLocal() {
        return mirror().isLocalClass();
    }

    @Override
    public boolean isMember() {
        return mirror().isMemberClass();
    }

    @Override
    public HotSpotResolvedObjectTypeImpl getEnclosingType() {
        final Class<?> encl = mirror().getEnclosingClass();
        return encl == null ? null : fromObjectClass(encl);
    }

    @Override
    public ResolvedJavaMethod[] getDeclaredConstructors() {
        Constructor<?>[] constructors = mirror().getDeclaredConstructors();
        ResolvedJavaMethod[] result = new ResolvedJavaMethod[constructors.length];
        for (int i = 0; i < constructors.length; i++) {
            result[i] = runtime().getHostJVMCIBackend().getMetaAccess().lookupJavaMethod(constructors[i]);
            assert result[i].isConstructor();
        }
        return result;
    }

    @Override
    public ResolvedJavaMethod[] getDeclaredMethods() {
        Method[] methods = mirror().getDeclaredMethods();
        ResolvedJavaMethod[] result = new ResolvedJavaMethod[methods.length];
        for (int i = 0; i < methods.length; i++) {
            result[i] = runtime().getHostJVMCIBackend().getMetaAccess().lookupJavaMethod(methods[i]);
            assert !result[i].isConstructor();
        }
        return result;
    }

    public ResolvedJavaMethod getClassInitializer() {
        return compilerToVM().getClassInitializer(this);
    }

    @Override
    public String toString() {
        return "HotSpotType<" + getName() + ", resolved>";
    }

    @Override
    public boolean isCloneableWithAllocation() {
        return (getAccessFlags() & config().jvmAccIsCloneableFast) != 0;
    }
}
