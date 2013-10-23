/*
 * Copyright 2011 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.factmodel.traits;

import org.drools.KnowledgeBase;
import org.drools.RuleBase;
import org.drools.RuntimeDroolsException;
import org.drools.base.ClassFieldAccessor;
import org.drools.base.ClassFieldAccessorStore;
import org.drools.common.AbstractRuleBase;
import org.drools.core.util.TripleFactory;
import org.drools.core.util.TripleStore;
import org.drools.core.util.asm.ClassFieldInspector;
import org.drools.factmodel.BuildUtils;
import org.drools.factmodel.ClassBuilderFactory;
import org.drools.factmodel.ClassDefinition;
import org.drools.factmodel.FieldDefinition;
import org.drools.factmodel.MapCore;
import org.drools.impl.KnowledgeBaseImpl;
import org.drools.reteoo.ReteooComponentFactory;
import org.drools.rule.JavaDialectRuntimeData;
import org.drools.rule.Package;
import org.drools.spi.InternalReadAccessor;
import org.drools.spi.KnowledgeHelper;
import org.drools.util.HierarchyEncoder;
import org.mvel2.asm.MethodVisitor;
import org.mvel2.asm.Opcodes;
import org.mvel2.asm.Type;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

public class TraitFactory<T extends Thing<K>, K extends TraitableBean> implements Opcodes, Externalizable {

//    private static TripleStore store = new TripleStore( 500, 0.6f );

    public enum VirtualPropertyMode { MAP, TRIPLES }

    private VirtualPropertyMode mode = VirtualPropertyMode.TRIPLES;

    public final static String SUFFIX = "_Trait__Extension";

    private static final String pack = "org.drools.factmodel.traits.";

    private Map<String, Constructor> factoryCache = new HashMap<String, Constructor>();

    private Map<Class, Class<? extends CoreWrapper<?>>> wrapperCache = new HashMap<Class, Class<? extends CoreWrapper<?>>>();

    private transient AbstractRuleBase ruleBase;
    
    
    public static void setMode( VirtualPropertyMode newMode, KnowledgeBase kBase ) {
        RuleBase ruleBase = ((KnowledgeBaseImpl) kBase).getRuleBase();
        ReteooComponentFactory rcf = ((AbstractRuleBase) ruleBase).getConfiguration().getComponentFactory();
        ClassBuilderFactory cbf = rcf.getClassBuilderFactory();
        rcf.getTraitFactory().mode = newMode;
        switch ( newMode ) {
            case MAP    :
                cbf.setPropertyWrapperBuilder( new TraitMapPropertyWrapperClassBuilderImpl() );
                cbf.setTraitProxyBuilder( new TraitMapProxyClassBuilderImpl() );
                break;
            case TRIPLES:
                cbf.setPropertyWrapperBuilder( new TraitTriplePropertyWrapperClassBuilderImpl() );
                cbf.setTraitProxyBuilder( new TraitTripleProxyClassBuilderImpl() );
                break;
            default     :   throw new RuntimeException( " This should not happen : unexpected property wrapping method " + newMode );
        }

    }

    public static TraitFactory getTraitBuilderForKnowledgeBase( KnowledgeBase kb ) {
        AbstractRuleBase arb = (AbstractRuleBase) ((KnowledgeBaseImpl) kb ).getRuleBase();
        return arb.getConfiguration().getComponentFactory().getTraitFactory();
    }



    public TraitFactory() {        
    }


    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject( mode );
        out.writeObject( factoryCache );
        out.writeObject( wrapperCache );
    }

    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        mode = (VirtualPropertyMode) in.readObject();
        factoryCache = (Map<String, Constructor>) in.readObject();
        wrapperCache = (Map<Class, Class<? extends CoreWrapper<?>>>) in.readObject();
    }
    


    @Deprecated()
    /**
     * Test compatiblity only, do not use
     */
    public T getProxy( K core, Class<?> trait ) throws LogicalTypeInconsistencyException {
        return getProxy( core, trait, false );
    }

    public T getProxy( K core, Class<?> trait, boolean logical ) throws LogicalTypeInconsistencyException {
        String traitName = trait.getName();

        if ( core.hasTrait( traitName ) ) {
            return (T) core.getTrait( traitName );
        }

        String key = getKey( core.getClass(), trait );

        Constructor<T> konst;
        synchronized ( factoryCache ) {
             konst = factoryCache.get( key );
            if ( konst == null ) {
                konst = cacheConstructor( key, core, trait );
            }
        }

        T proxy = null;
        HierarchyEncoder hier = ruleBase.getConfiguration().getComponentFactory().getTraitRegistry().getHierarchy();
        try {
            switch ( mode ) {
                case MAP    :   proxy = konst.newInstance( core, core._getDynamicProperties(), hier.getCode( trait.getName() ), hier.getBottom(), logical );
                    break;
                case TRIPLES:   proxy = konst.newInstance( core, ruleBase.getTripleStore(), getTripleFactory(), hier.getCode( trait.getName() ), hier.getBottom(), logical );
                    break;
                default     :   throw new RuntimeException( " This should not happen : unexpected property wrapping method " + mode );
            }

            return proxy;
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        throw new LogicalTypeInconsistencyException( "Could not apply trait " + trait + " to object " + core, trait, core.getClass() );
    }


    public AbstractRuleBase getRuleBase() {
        return ruleBase;
    }

    public void setRuleBase( AbstractRuleBase ruleBase ) {
        this.ruleBase = ruleBase;        
    }


    private Constructor<T> cacheConstructor( String key, K core, Class<?> trait ) {
        Class<T> proxyClass = buildProxyClass( key, core, trait );
        if ( proxyClass == null ) {
            return null;
        }
        try {
            Constructor konst;
            switch ( mode ) {
                case MAP    :   konst = proxyClass.getConstructor( core.getClass(), Map.class, BitSet.class, BitSet.class, boolean.class );
                    break;
                case TRIPLES:   konst = proxyClass.getConstructor( core.getClass(), TripleStore.class, TripleFactory.class, BitSet.class, BitSet.class, boolean.class );
                    break;
                default     :   throw new RuntimeException( " This should not happen : unexpected property wrapping method " + mode );
            }

            factoryCache.put( key, konst );
            return konst;
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
            return null;
        }
    }


    public static String getProxyName( ClassDefinition trait, ClassDefinition core ) {
        return getKey( core.getDefinedClass(), trait.getDefinedClass() ) + "_Proxy";
    }

    public static String getPropertyWrapperName( ClassDefinition trait, ClassDefinition core ) {
        return getKey( core.getDefinedClass(), trait.getDefinedClass() ) + "_ProxyWrapper";
    }

    private static String getKey( Class core, Class trait  ) {
        return ( trait.getName() + "." + core.getName() );
    }


    public static String getSoftFieldKey( String fieldName, Class fieldType, Class trait, Class core ) {
            return fieldName;
    }




    private Class<T> buildProxyClass( String key, K core, Class<?> trait ) {

        Class coreKlass = core.getClass();


        // get the trait classDef
        ClassDefinition tdef = ruleBase.getTraitRegistry().getTrait( trait.getName() );
        ClassDefinition cdef = ruleBase.getTraitRegistry().getTraitable( coreKlass.getName() );

        if ( tdef == null ) {
            throw new RuntimeDroolsException( "Unable to find Trait definition for class " + trait.getName() + ". It should have been DECLARED as a trait" );
        }
        if ( cdef == null ) {
            throw new RuntimeDroolsException( "Unable to find Core class definition for class " + coreKlass.getName() + ". It should have been DECLARED as a trait" );
        }

        String proxyName = getProxyName( tdef, cdef );
        String wrapperName = getPropertyWrapperName( tdef, cdef );

        ReteooComponentFactory rcf = ruleBase.getConfiguration().getComponentFactory();


        TraitPropertyWrapperClassBuilder propWrapperBuilder = (TraitPropertyWrapperClassBuilder) rcf.getClassBuilderFactory().getPropertyWrapperBuilder();

        propWrapperBuilder.init( tdef, ruleBase.getTraitRegistry() );
        try {
            byte[] propWrapper = propWrapperBuilder.buildClass( cdef );
            ruleBase.registerAndLoadTypeDefinition( wrapperName, propWrapper );
        } catch (Exception e) {
            e.printStackTrace();
        }


        TraitProxyClassBuilder proxyBuilder = (TraitProxyClassBuilder) rcf.getClassBuilderFactory().getTraitProxyBuilder();

        proxyBuilder.init( tdef, rcf.getBaseTraitProxyClass(), ruleBase.getTraitRegistry() );
        try {
            byte[] proxy = proxyBuilder.buildClass( cdef );
            ruleBase.registerAndLoadTypeDefinition( proxyName, proxy );
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            BitSet mask = ruleBase.getTraitRegistry().getFieldMask( trait.getName(), cdef.getDefinedClass().getName() );
            Class<T> proxyClass = (Class<T>) ruleBase.getRootClassLoader().loadClass( proxyName, true );
            bindAccessors( proxyClass, tdef, cdef, mask );
            Class<T> wrapperClass = (Class<T>) ruleBase.getRootClassLoader().loadClass( wrapperName, true );
            bindCoreAccessors( wrapperClass, cdef );
            return proxyClass;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void bindAccessors( Class<T> proxyClass, ClassDefinition tdef, ClassDefinition cdef, BitSet mask ) {
        int j = 0;
        for ( FieldDefinition traitField : tdef.getFieldsDefinitions() ) {
            boolean isSoftField = TraitRegistry.isSoftField( traitField, j++, mask );
            if ( ! isSoftField ) {
                String traitFieldHook = traitField.resolveAlias( );
                FieldDefinition field = cdef.getFieldByAlias( traitFieldHook );

                Field staticField;
                try {
                    if ( ( cdef.isFullTraiting() && ( ! traitField.getType().isPrimitive() || field.getType().equals( traitField.getType() ) ) )
                         || field.getType().isAssignableFrom( traitField.getType() ) ) {
                        staticField = proxyClass.getField( traitField.getName() + "_reader" );
                        staticField.set( null, field.getFieldAccessor().getReadAccessor() );

                        staticField = proxyClass.getField( traitField.getName() + "_writer" );
                        staticField.set( null, field.getFieldAccessor().getWriteAccessor() );
                    }
                } catch (NoSuchFieldException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (IllegalAccessException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            }
        }
    }

    private void bindCoreAccessors( Class<T> wrapperClass, ClassDefinition cdef ) {
        for ( FieldDefinition field : cdef.getFieldsDefinitions() ) {
            Field staticField;
            try {
                staticField = wrapperClass.getField(field.getName() + "_reader");
                staticField.set(null, field.getFieldAccessor().getReadAccessor() );

                staticField = wrapperClass.getField(field.getName() + "_writer");
                staticField.set(null, field.getFieldAccessor().getWriteAccessor() );
            } catch (NoSuchFieldException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            } catch (IllegalAccessException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
    }



    private Package getPackage(String pack) {
        Package pkg = ruleBase.getPackage( pack );
        if ( pkg == null ) {
            pkg = new Package( pack );
            JavaDialectRuntimeData data = new JavaDialectRuntimeData();
            pkg.getDialectRuntimeRegistry().setDialectData( "java", data );
            data.onAdd(pkg.getDialectRuntimeRegistry(),
                    ruleBase.getRootClassLoader());
            ruleBase.addPackages( Arrays.asList(pkg) );
        }
        return pkg;

    }
















    public synchronized CoreWrapper<K> getCoreWrapper( Class<K> coreKlazz , ClassDefinition coreDef ) {
        if ( wrapperCache == null ) {
            wrapperCache = new HashMap<Class, Class<? extends CoreWrapper<?>>>();
        }
        Class<? extends CoreWrapper<K>> wrapperClass = null;
        if ( wrapperCache.containsKey( coreKlazz ) ) {
            wrapperClass = (Class<? extends CoreWrapper<K>>) wrapperCache.get( coreKlazz );
        } else {
            try {
                wrapperClass = buildCoreWrapper( coreKlazz, coreDef );
            } catch (IOException e) {
                return null;
            } catch (ClassNotFoundException e) {
                return null;
            }
            wrapperCache.put( coreKlazz, wrapperClass );
        }

        try {
            ruleBase.getTraitRegistry().addTraitable( buildWrapperClassDefinition( coreKlazz, wrapperClass ) );
            return wrapperClass != null ? wrapperClass.newInstance() : null;
        } catch (InstantiationException e) {
            return null;
        } catch (IllegalAccessException e) {
            return null;
        } catch (IOException e) {
            return null;
        }

    }

    private ClassDefinition buildWrapperClassDefinition(Class<K> coreKlazz, Class<? extends CoreWrapper<K>> wrapperClass) throws IOException {
        ClassFieldInspector inspector = new ClassFieldInspector( coreKlazz );

        Package traitPackage = ruleBase.getPackagesMap().get( pack );
        if ( traitPackage == null ) {
            traitPackage = new Package( pack );
            traitPackage.setClassFieldAccessorCache( ruleBase.getClassFieldAccessorCache() );
            ruleBase.getPackagesMap().put( pack, traitPackage );
        }
        ClassFieldAccessorStore store = traitPackage.getClassFieldAccessorStore();

        String className = coreKlazz.getName() + "Wrapper";
        String superClass = coreKlazz.getName();
        String[] interfaces = new String[] {CoreWrapper.class.getName()};
        ClassDefinition def = new ClassDefinition( className, superClass, interfaces );
        Traitable tbl = wrapperClass.getAnnotation( Traitable.class );
        def.setTraitable( true, tbl != null && tbl.logical() );
        def.setDefinedClass( wrapperClass );

        Map<String, Field> fields = inspector.getFieldTypesField();
        for ( Field f : fields.values() ) {
            if ( f != null ) {
                FieldDefinition fld = new FieldDefinition();
                fld.setName( f.getName() );
                fld.setTypeName( f.getType().getName() );
                fld.setInherited( true );
                ClassFieldAccessor accessor = store.getAccessor( def.getDefinedClass().getName(),
                        fld.getName() );
                fld.setReadWriteAccessor( accessor );

                def.addField( fld );
            }
        }


        return def;
    }

    private Class<CoreWrapper<K>> buildCoreWrapper( Class<K> coreKlazz, ClassDefinition coreDef ) throws IOException, ClassNotFoundException {

        String coreName = coreKlazz.getName();
        String wrapperName = coreName + "Wrapper";

        try {
            byte[] wrapper = new TraitCoreWrapperClassBuilderImpl().buildClass( coreDef );
            ruleBase.registerAndLoadTypeDefinition( wrapperName, wrapper );
//            JavaDialectRuntimeData data = ((JavaDialectRuntimeData) getPackage( pack ).getDialectRuntimeRegistry().
//                getDialectData( "java" ));

//            String resourceName = JavaDialectRuntimeData.convertClassToResourcePath( wrapperName );
//            data.putClassDefinition( resourceName, wrapper );
//            data.write( resourceName, wrapper );


//            data.onBeforeExecute();
        } catch ( Exception e ) {
            e.printStackTrace();
        }

        Class<CoreWrapper<K>> wrapperClass = (Class<CoreWrapper<K>>) ruleBase.getRootClassLoader().loadClass( wrapperName, true );
        return wrapperClass;
    }




















    public static void valueOf( MethodVisitor mv, String type ) {
        mv.visitMethodInsn( INVOKESTATIC,
                BuildUtils.getInternalType( BuildUtils.box( type ) ),
                "valueOf",
                "(" + BuildUtils.getTypeDescriptor( type ) + ")" +
                        BuildUtils.getTypeDescriptor( BuildUtils.box( type ) )
        );

    }


    public static void primitiveValue( MethodVisitor mv, String fieldType ) {
        mv.visitTypeInsn( CHECKCAST, BuildUtils.getInternalType( BuildUtils.box( fieldType ) ) );
        mv.visitMethodInsn(
                INVOKEVIRTUAL,
                BuildUtils.getInternalType( BuildUtils.box( fieldType ) ),
                fieldType + "Value",
                "()"+ BuildUtils.getTypeDescriptor( fieldType ) );
    }


    public static void invokeExtractor( MethodVisitor mv, String masterName, ClassDefinition trait, ClassDefinition core, FieldDefinition field ) {
        String fieldType = field.getTypeName();
        mv.visitFieldInsn( GETSTATIC,
                BuildUtils.getInternalType( masterName ),
                field.getName() + "_reader",
                Type.getDescriptor( InternalReadAccessor.class ) );

        mv.visitVarInsn( ALOAD, 0 );
        mv.visitFieldInsn( GETFIELD,
                BuildUtils.getInternalType( masterName ),
                "object",
                BuildUtils.getTypeDescriptor( core.getClassName() ) );

        String returnType = BuildUtils.isPrimitive( fieldType ) ? BuildUtils.getTypeDescriptor( fieldType ) : Type.getDescriptor( Object.class );
        mv.visitMethodInsn( INVOKEINTERFACE,
                Type.getInternalName( InternalReadAccessor.class ),
                BuildUtils.extractor( fieldType ),
                Type.getMethodDescriptor( Type.getType( returnType ), new Type[] { Type.getType( Object.class ) } ) );


    }


    public static void invokeInjector( MethodVisitor mv, String masterName, ClassDefinition source, ClassDefinition target, FieldDefinition field, boolean toNull, int pointer ) {
        String fieldName = field.getName();
        String fieldType = field.getTypeName();
        mv.visitFieldInsn( GETSTATIC,
                BuildUtils.getInternalType( masterName ),
                fieldName + "_writer",
                "Lorg/drools/spi/WriteAccessor;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn( GETFIELD,
                BuildUtils.getInternalType( masterName ),
                "object",
                BuildUtils.getTypeDescriptor( target.getName() ) );

        if ( toNull ) {
            mv.visitInsn( BuildUtils.zero( field.getTypeName() ) );
        } else {
            mv.visitVarInsn( BuildUtils.varType( fieldType ), pointer );
        }
        String argType = BuildUtils.isPrimitive( fieldType ) ?
                BuildUtils.getTypeDescriptor( fieldType ) :
                "Ljava/lang/Object;";
        mv.visitMethodInsn( INVOKEINTERFACE,
                "org/drools/spi/WriteAccessor",
                BuildUtils.injector( fieldType ),
                "(Ljava/lang/Object;" + argType + ")V");

    }


    public static String buildSignature( Method method ) {
        String sig = "(";
        for ( Class arg : method.getParameterTypes() ) {
            sig += BuildUtils.getTypeDescriptor( arg.getName() );
        }
        sig += ")";
        sig += BuildUtils.getTypeDescriptor( method.getReturnType().getName() );
        return sig;
    }


    public static int getStackSize( Method m ) {
        int stack = 1;
        for ( Class klass : m.getParameterTypes() ) {
            stack += BuildUtils.sizeOf( klass.getName() );
        }
        return stack;
    }

    public TripleFactory getTripleFactory() {
        return ruleBase.getConfiguration().getComponentFactory().getTripleFactory();
    }


}