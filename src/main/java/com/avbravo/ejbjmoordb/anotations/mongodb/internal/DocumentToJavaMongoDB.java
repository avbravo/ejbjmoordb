/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.avbravo.ejbjmoordb.anotations.mongodb.internal;

import com.avbravo.ejbjmoordb.EmbeddedBeans;
import com.avbravo.ejbjmoordb.JmoordbException;
import com.avbravo.ejbjmoordb.ReferencedBeans;
import com.avbravo.ejbjmoordb.util.ClassDescriptor;
import com.avbravo.ejbjmoordb.util.ClassDescriptorsCache;
import com.avbravo.ejbjmoordb.util.FieldDescriptor;
import com.avbravo.ejbjmoordb.util.ReflectionUtils;
import com.avbravo.ejbjmoordb.util.Test;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.util.AnnotationLiteral;
import javax.faces.context.FacesContext;
import javax.naming.InitialContext;
import javax.servlet.ServletContext;
import org.bson.Document;

/**
 *
 * @author avbravo
 */
public class DocumentToJavaMongoDB<T> {

    private static final Logger LOG = Logger.getLogger(DocumentToJavaMongoDB.class.getName());

    private ClassDescriptorsCache cache = new ClassDescriptorsCache();
    List<EmbeddedBeans> embeddedBeansList = new ArrayList<>();
    List<ReferencedBeans> referencedBeansList = new ArrayList<>();
    ReferencedBeans referencedBeans = new ReferencedBeans();
    T t1;

    @SuppressWarnings("unchecked")
    public <T> T fromDocument(Class<T> clazz, Document dbObject, List<EmbeddedBeans> embeddedBeansList, List<ReferencedBeans> referencedBeansList) {

        if (dbObject == null) {
            return null;
        }
        this.embeddedBeansList = embeddedBeansList;
        this.referencedBeansList = referencedBeansList;
        ClassDescriptor classDescriptor = cache.get(clazz);
        Object object = classDescriptor.newInstance();
        for (FieldDescriptor fieldDescriptor : classDescriptor.getFields()) {
            try {
                //Test.msg(" Analizando: " + fieldDescriptor.getName());
                fieldDescriptor.getField().set(object,
                        fromDocumentRecursive(dbObject.get(fieldDescriptor.getName()), fieldDescriptor));
            } catch (Exception e) {
                throw new JmoordbException("Failed to set field value " + fieldDescriptor.getName(), e);
            }
        }
        return (T) object;
    }

    /**
     *
     * @param <T>
     * @param clazz
     * @param dbObject
     * @return
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object fromDocumentRecursive(Object dbObject, FieldDescriptor fieldDescriptor) {
        try {
            //Test.msg("==================================================");
            //Test.msg("  fromDocumentRecursive: " + fieldDescriptor.getName());

            if (dbObject == null) {

                return fieldDescriptor.getDefaultValue();
            }

            Class<?> fieldType = fieldDescriptor.getField().getType();
            if (fieldDescriptor.isSimple()) {
                //Test.msg("   [isSimple] " + fieldDescriptor.getSimpleValue(dbObject));
                return fieldDescriptor.getSimpleValue(dbObject);
            } else if (fieldDescriptor.isArray()) {
                //Test.msg("   [ isArray]");
                BasicDBList dbList = (BasicDBList) dbObject;
                if (fieldType.getComponentType().isPrimitive()) {

                    return ReflectionUtils.dbListToArrayOfPrimitives(dbList, fieldType);
                }
                List list = new ArrayList();
                for (Object listEl : dbList) {

                    if (listEl == null || ReflectionUtils.isSimpleClass(listEl.getClass())) {

                        list.add(listEl);
                    } else {

                        list.add(fromDocument((Class<Object>) fieldType.getComponentType(), (Document) listEl, embeddedBeansList, referencedBeansList));
                    }
                }

                Object[] arrayPrototype = (Object[]) Array.newInstance(fieldType.getComponentType(), 0);
                return list.toArray(arrayPrototype);
            } else if (fieldDescriptor.isList()) {
                //Test.msg(" [isList()  ]" + fieldDescriptor.getName());
                if (isEmbedded(fieldDescriptor.getName())) {
                    //     //Test.msg("     [es Embebido]");

                    List<BasicDBObject> dbList = (ArrayList<BasicDBObject>) dbObject;

                    List list = (List) fieldDescriptor.newInstance();

                    for (Object listEl : dbList) {

                        if (ReflectionUtils.isSimpleClass(listEl.getClass())) {

                            list.add(listEl);
                        } else {

                            list.add(fromDocument(ReflectionUtils.genericType(fieldDescriptor.getField()), (Document) listEl, embeddedBeansList, referencedBeansList));
                        }
                    }

                    return list;
                } else {
                    if (isReferenced(fieldDescriptor.getName())) {
                        //Referenciado
                        //Test.msg("     [es Referenciado]");
                        if (referencedBeans.getLazy()) {
                            //Test.msg("[    Lazy == true no carga los relacionados ]");

                            List<BasicDBObject> dbList = (ArrayList<BasicDBObject>) dbObject;
                            List list = (List) fieldDescriptor.newInstance();
                            for (Object listEl : dbList) {
                                if (ReflectionUtils.isSimpleClass(listEl.getClass())) {
                                    list.add(listEl);
                                } else {
                                    list.add(fromDocument(ReflectionUtils.genericType(fieldDescriptor.getField()), (Document) listEl, embeddedBeansList, referencedBeansList));
                                }
                            }

                            return list;
                        } else {
                            //Test.msg("[    Lazy == false carga los relacionados ]");

                            List<BasicDBObject> dbList = (ArrayList<BasicDBObject>) dbObject;
                            List list = (List) fieldDescriptor.newInstance();

                            for (Object listEl : dbList) {

                                if (ReflectionUtils.isSimpleClass(listEl.getClass())) {
                                    list.add(listEl);
                                } else {
                                    Document doc = (Document) listEl;
                                    Class[] paramString = new Class[2];
                                    paramString[0] = String.class;
                                    Class cls = Class.forName(referencedBeans.getFacade());
//                                    Object obj = cls.newInstance();
      Object obj = lookUpClassInBeanManager(cls);
                                    Method method;
                                    String value = "";
                                    if (referencedBeans.getJavatype().toLowerCase().equals("integer")) {
                                        //@Id de tipo Integer
                                        Integer n = (Integer) doc.get(referencedBeans.getField());
                                        method = cls.getDeclaredMethod("findById", String.class, Integer.class);
////Test.msg(" voy a optional Integer");

                                        t1 = (T) method.invoke(obj, referencedBeans.getField(), n);

                                    } else {
                                        //Test.msg(" voy a optional String");
                                        value = (String) doc.get(referencedBeans.getField());
                                        paramString[1] = String.class;
                                        method = cls.getDeclaredMethod("findById", paramString);

                                        String[] param = {referencedBeans.getField(), value};

                                        t1 = (T) method.invoke(obj, param);
                                    }

                                    list.add(t1);

                                }
                            }

                            return list;
                        }

                    } else {
                        //Test.msg("    No es[Embebido] ni  [Referenciado]");
                        List<BasicDBObject> foundDocument = (ArrayList<BasicDBObject>) dbObject;
                        List list = (List) fieldDescriptor.newInstance();

                        for (Object listEl : foundDocument) {
                            if (ReflectionUtils.isSimpleClass(listEl.getClass())) {
                                list.add(listEl);
                            } else {

                                list.add(fromDocument(ReflectionUtils.genericType(fieldDescriptor.getField()), (Document) listEl, embeddedBeansList, referencedBeansList));
                            }
                        }

                        return list;
                    }
                }

            } else if (fieldDescriptor.isSet()) {
                //Test.msg(" [isSet()  ]");
                BasicDBList dbList = (BasicDBList) dbObject;
                Set set = (Set) fieldDescriptor.newInstance();
                for (Object listEl : dbList) {

                    if (ReflectionUtils.isSimpleClass(listEl.getClass())) {

                        set.add(listEl);
                    } else {

                        set.add(fromDocument(ReflectionUtils.genericType(fieldDescriptor.getField()), (Document) listEl, embeddedBeansList, referencedBeansList));
                    }
                }
                return set;
            } else if (fieldDescriptor.isMap()) {
                //Test.msg(" isMap()  ]");
                DBObject dbMap = (DBObject) dbObject;
                Map map = (Map) fieldDescriptor.newInstance();
                for (Object key : dbMap.keySet()) {

                    Object mapEl = dbMap.get(key.toString());
                    if (mapEl == null || ReflectionUtils.isSimpleClass(mapEl.getClass())) {

                        map.put(key, mapEl);
                    } else {

                        map.put(key,
                                fromDocument(ReflectionUtils.genericTypeOfMapValue(fieldDescriptor.getField()),
                                        (Document) mapEl, embeddedBeansList, referencedBeansList));
                    }
                }
                return map;
            } else if (fieldDescriptor.isObject()) {
                //Test.msg("   [isObject] " + fieldDescriptor.getName() + " ]");
                if (isEmbedded(fieldDescriptor.getName())) {
                    //Test.msg("  [es Embebido]");
                    Object object = fieldDescriptor.newInstance();
                    for (FieldDescriptor childDescriptor : fieldDescriptor.getChildren()) {
                        try {
                            childDescriptor.getField()
                                    .set(object,
                                            fromDocumentRecursive(((Document) dbObject).get(childDescriptor.getName()),
                                                    childDescriptor));
                        } catch (Exception e) {
                            throw new JmoordbException("[isObject]Failed to set field value " + childDescriptor.getName(), e);
                        }
                    }
                    return object;
                } else {
                    if (isReferenced(fieldDescriptor.getName())) {
                        //Referenciado
                        //Test.msg("         [es Referenciado] ");
                        if (referencedBeans.getLazy()) {
                            //Test.msg("[    {Lazy == true} No carga los relacionados ]");
                            Object object = fieldDescriptor.newInstance();
                            for (FieldDescriptor childDescriptor : fieldDescriptor.getChildren()) {
                                try {
                                    if (childDescriptor.getField().getName().equals(referencedBeans.getField())) {

                                        childDescriptor.getField()
                                                .set(object,
                                                        fromDocumentRecursive(((Document) dbObject).get(childDescriptor.getName()),
                                                                childDescriptor));
                                    }
                                } catch (Exception e) {
                                    throw new JmoordbException("Failed to set field value " + childDescriptor.getName(), e);
                                }
                            }
                            return object;
//                       
                        } else {
  //Test.msg("                 [   Lazy == false carga los relacionados ]");
                            Class cls = Class.forName(referencedBeans.getFacade());

                            Object obj = lookUpClassInBeanManager(cls);
                            Method method;

                            //             
                            if (referencedBeans.getJavatype().toLowerCase().equals("integer")) {
                                //@Id de tipo Integer
                                Class[] paramString = new Class[2];
                                method = cls.getDeclaredMethod("findById", String.class, Integer.class);

                                Integer value = 0;
                                for (FieldDescriptor childDescriptor : fieldDescriptor.getChildren()) {
                                    if (childDescriptor.getField().getName().equals(referencedBeans.getField())) {
                                        Object x = ((Document) dbObject).get(childDescriptor.getName());
                                        value = (Integer) childDescriptor.getSimpleValue(x);
                                    }
                                }

                                t1 = (T) method.invoke(obj, referencedBeans.getField(), value);

                            } else {
                                Class[] paramString = new Class[2];
                                paramString[0] = String.class;
                                paramString[1] = String.class;
                                method = cls.getDeclaredMethod("findById", paramString);

                                String value = "";
                                for (FieldDescriptor childDescriptor : fieldDescriptor.getChildren()) {
                                    if (childDescriptor.getField().getName().equals(referencedBeans.getField())) {
                                        Object x = ((Document) dbObject).get(childDescriptor.getName());
                                        value = (String) childDescriptor.getSimpleValue(x);
                                    }
                                }
                                String[] param = {referencedBeans.getField(), value};
                                t1 = (T) method.invoke(obj, param);
                            }

                            return t1;

                        }
                    } else {
                        //Test.msg("                   [No es Referenced]");
                        new JmoordbException("@Embedded or @Reference is required for this field " + fieldDescriptor.getName());
                        return new Document();
                    }
                }

            }
        } catch (Exception e) {
            throw new JmoordbException("fromDocumentRecursive() " + fieldDescriptor.getName(), e);
        }
        return null;
    }

//    public Object getBeanByName(String name) // eg. name=availableCountryDao
//    {
//        Object o = null;
//        try {
//             BeanManager bm = getBeanManager();
//        Bean bean = bm.getBeans(name).iterator().next();
//        CreationalContext ctx = bm.createCreationalContext(bean); // could be inlined below
//     o = bm.getReference(bean, bean.getClass(), ctx); // could be inlined with
//        } catch (Exception e) {
//            LOG.warning("getBeanByName() "+e.getLocalizedMessage());
//            //Test.msg("getBeanByName() "+e.getLocalizedMessage());
//        }
//  
//        return o;
//    }
//     public static <T> T getBean(Class<T> clazz) {
//    BeanManager bm = getBeanManager();
//    Bean<T> bean = (Bean<T>) bm.getBeans(clazz).iterator().next();
//    CreationalContext<T> ctx = bm.createCreationalContext(bean);
//    return (T) bm.getReference(bean, clazz, ctx);
//  }
//
//    private static BeanManager getBeanManager() {
//        ServletContext servletContext = (ServletContext) FacesContext
//                .getCurrentInstance().getExternalContext().getContext();
//        return (BeanManager) servletContext
//                .getAttribute("javax.enterprise.inject.spi.BeanManager");
//    }
    private Boolean isEmbedded(String name) {
        try {
            if (embeddedBeansList.stream().anyMatch((eb) -> (eb.getName().equals(name)))) {
                return true;
            }
            return false;
        } catch (Exception e) {
            new JmoordbException("isEmbedded() " + e.getLocalizedMessage());
        }
        return false;
    }

    /**
     *
     * @param name
     * @return
     */
    private Boolean isReferenced(String name) {
        try {

            for (ReferencedBeans eb : referencedBeansList) {
                if (eb.getName().equals(name)) {
                    referencedBeans = eb;
                    //Test.msg("Referenced() " + eb.toString());
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            new JmoordbException("isReferenced() " + e.getLocalizedMessage());
        }
        return false;
    }

    private static <T> T lookUpClassInBeanManager(Class<T> clazz) {
        BeanManager bm = CDI.current().getBeanManager();
        Bean<T> bean = (Bean<T>) bm.getBeans(clazz).iterator().next();
        CreationalContext<T> ctx = bm.createCreationalContext(bean);
        return (T) bm.getReference(bean, clazz, ctx);
    }
}
