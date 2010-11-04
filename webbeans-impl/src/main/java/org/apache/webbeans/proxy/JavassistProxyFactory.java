/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.webbeans.proxy;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.Decorator;

import org.apache.webbeans.annotation.WebBeansAnnotation;
import org.apache.webbeans.component.InjectionTargetBean;
import org.apache.webbeans.component.OwbBean;
import org.apache.webbeans.config.WebBeansFinder;
import org.apache.webbeans.context.creational.CreationalContextImpl;
import org.apache.webbeans.decorator.WebBeansDecorator;
import org.apache.webbeans.intercept.ApplicationScopedBeanIntereptorHandler;
import org.apache.webbeans.intercept.DependentScopedBeanInterceptorHandler;
import org.apache.webbeans.intercept.InterceptorData;
import org.apache.webbeans.intercept.NormalScopedBeanInterceptorHandler;
import org.apache.webbeans.intercept.webbeans.WebBeansInterceptor;
import org.apache.webbeans.util.ClassUtil;
import org.apache.webbeans.util.SecurityUtil;
import org.apache.webbeans.util.WebBeansUtil;

public final class JavassistProxyFactory
{
    public JavassistProxyFactory()
    {

    }
    
    private ConcurrentMap<OwbBean<?>, Class<?>> normalScopedBeanProxyClasses = new ConcurrentHashMap<OwbBean<?>, Class<?>>();    
    private ConcurrentMap<OwbBean<?>, Class<?>> dependentScopedBeanProxyClasses = new ConcurrentHashMap<OwbBean<?>, Class<?>>();    
    private ConcurrentMap<OwbBean<?>, Class<?>> interceptorProxyClasses = new ConcurrentHashMap<OwbBean<?>, Class<?>>();    
    // second level map is indexed on local interface
    private ConcurrentMap<OwbBean<?>, ConcurrentMap<Class<?>, Class<?>>> ejbProxyClasses = new ConcurrentHashMap<OwbBean<?>, ConcurrentMap<Class<?>, Class<?>>>();    
    
    public   Map<OwbBean<?>, Class<?>> getInterceptorProxyClasses()
    {
        return interceptorProxyClasses;
    }
    
    public static JavassistProxyFactory getInstance()
    {
        JavassistProxyFactory aef = (JavassistProxyFactory) WebBeansFinder.getSingletonInstance(JavassistProxyFactory.class.getName());
        return aef;
    }
    
    public void clear()
    {
        normalScopedBeanProxyClasses.clear();
        dependentScopedBeanProxyClasses.clear();
        interceptorProxyClasses.clear();
        ejbProxyClasses.clear();
    }
    /**
     * Provides the proxy for the given bean and interface, if defined
     * 
     * @param bean the contextual representing the EJB
     * @param iface the injected business local interface
     * @return the proxy Class if one has been defined, else null
     */
    public Class<?> getEjbBeanProxyClass(OwbBean<?> bean, Class<?> iface)
    {
        Class<?> proxyClass = null;
        ConcurrentMap<Class<?>, Class<?>> typeToProxyClassMap = ejbProxyClasses.get(bean);
        if (typeToProxyClassMap != null)
        {
            proxyClass = typeToProxyClassMap.get(iface);
        }
        return proxyClass;
    }

    /**
     * Defines the proxy for the given bean and iface using callers factory. Due
     * to races with the concurrentmap, this might sometimes create additional
     * javassist-defined classes that are not used by the caller and not stored
     * in the map. Synchronizing the entire method, and getEjbBeanProxyClass, on
     * ejbProxyClasses is an alternative.
     * 
     * @param bean the contextual representing the EJB
     * @param iface the injected business local interface
     * @param factory
     * @return
     */
    public Class<?> defineEjbBeanProxyClass(OwbBean<?> bean, Class<?> iface, ProxyFactory factory)
    {
        Class<?> proxyClass = null;

        ConcurrentMap<Class<?>, Class<?>> typeToProxyClassMap = ejbProxyClasses.get(bean);
        if (typeToProxyClassMap == null)
        {
            typeToProxyClassMap = new ConcurrentHashMap<Class<?>, Class<?>>();
            ConcurrentMap<Class<?>, Class<?>> existingMap = ejbProxyClasses.putIfAbsent(bean, typeToProxyClassMap);
            
            // use the map that beat us, because our new one definitely had no classes in it.
            typeToProxyClassMap = (existingMap != null) ? existingMap : typeToProxyClassMap; 
        }

        proxyClass = typeToProxyClassMap.get(iface);

        if (proxyClass == null)
        {
            proxyClass = SecurityUtil.doPrivilegedCreateClass(factory);
            typeToProxyClassMap.putIfAbsent(iface, proxyClass);
            // don't care if we were beaten in updating the iface->proxyclass map
        }

        return proxyClass;
    }
    
    public  Class<?> createAbstractDecoratorProxyClass(OwbBean<?> bean)
    {
        //Will only get called once while defining the bean, so no need to cache
        Class<?> clazz = null;
        try
        {
            ProxyFactory fact = createProxyFactory(bean);
            
            clazz = SecurityUtil.doPrivilegedCreateClass(fact);
        }
        catch(Exception e)
        {
            WebBeansUtil.throwRuntimeExceptions(e);
        }
        return clazz;
        
    }
    
    public  Object createNormalScopedBeanProxy(OwbBean<?> bean, CreationalContext<?> creationalContext)
    {
        Object result = null;
        try
        {
            Class<?> proxyClass = normalScopedBeanProxyClasses.get(bean);
            if (proxyClass == null)
            {
                ProxyFactory fact = createProxyFactory(bean);

                proxyClass = getProxyClass(fact);
                normalScopedBeanProxyClasses.putIfAbsent(bean, proxyClass);
            }
            
            result = proxyClass.newInstance();
            
            if (!(bean instanceof WebBeansDecorator<?>) && !(bean instanceof WebBeansInterceptor<?>))
            {
                if (bean.getScope().equals(ApplicationScoped.class))
                {
                    ((ProxyObject)result).setHandler(new ApplicationScopedBeanIntereptorHandler(bean, creationalContext));
                }
                else 
                {
                    ((ProxyObject)result).setHandler(new NormalScopedBeanInterceptorHandler(bean, creationalContext));
                }
            }
        }
        catch (Exception e)
        {
            WebBeansUtil.throwRuntimeExceptions(e);
        }

        return result;
    }
    
    public  Object createDependentScopedBeanProxy(OwbBean<?> bean, Object actualInstance, CreationalContext<?> creastionalContext)
    {
        Object result = null;
        
        List<InterceptorData> interceptors =  null;
        List<Decorator<?>> decorators = null;
        InjectionTargetBean<?> injectionTargetBean = null;
        if(bean instanceof InjectionTargetBean<?>)
        {
            injectionTargetBean = (InjectionTargetBean<?>)bean;
            interceptors = injectionTargetBean.getInterceptorStack();
            decorators = injectionTargetBean.getDecoratorStack();
        }
        
        if(interceptors == null && decorators == null)
        {
            return actualInstance;
        }
        
        boolean notInInterceptorClassAndLifecycle = false;
        if(interceptors != null)
        {
            Iterator<InterceptorData> its = interceptors.iterator();
            while(its.hasNext())
            {
                InterceptorData id = its.next();
                if(!id.isDefinedInInterceptorClass() && id.isLifecycleInterceptor())
                {
                    continue;
                }
                notInInterceptorClassAndLifecycle = true;
                break;
            }
        }
        
        //No need to return proxy
        if(!notInInterceptorClassAndLifecycle && decorators.isEmpty())
        {
            //Adding this dependent instance into creational context
            //This occurs when no owner of this dependent instance
            if(creastionalContext instanceof CreationalContextImpl)
            {
                //If this creational context is owned by itself, add it
                //For example, getting it directly BeanManager#getReference(bean,creational context)
                CreationalContextImpl<?> ccImpl = (CreationalContextImpl<?>)creastionalContext;
                
                //Non contextual instance --> Bean --> Null
                //See OWBInjector
                if(ccImpl.getBean() != null)
                {
                    if(ccImpl.getBean().equals(bean))
                    {
                        //Owner of the dependent is itself
                        ccImpl.addDependent(actualInstance, bean, actualInstance);
                    }                                
                }
            }
            
            return actualInstance;
        }
        
        try
        {
            Class<?> proxyClass = dependentScopedBeanProxyClasses.get(bean);
            if (proxyClass == null)
            {
                ProxyFactory fact = createProxyFactory(bean);
                proxyClass = getProxyClass(fact);
                dependentScopedBeanProxyClasses.putIfAbsent(bean, proxyClass);
            }
            
            result = proxyClass.newInstance();
            if (!(bean instanceof WebBeansDecorator<?>) && !(bean instanceof WebBeansInterceptor<?>))
            {
                ((ProxyObject)result).setHandler(new DependentScopedBeanInterceptorHandler(bean, actualInstance, creastionalContext));
            }

        }
        catch (Exception e)
        {
            WebBeansUtil.throwRuntimeExceptions(e);
        }

        return result;
    }


    public  Class<?> getProxyClass(ProxyFactory factory)
    {
        Class<?> proxyClass = null;
        try
        {
            proxyClass = SecurityUtil.doPrivilegedCreateClass(factory);

        }
        catch(Exception e)
        {
            ProxyFactory.classLoaderProvider = new ProxyFactory.ClassLoaderProvider(){

                @Override
                public ClassLoader get(ProxyFactory pf)
                {
                    return Thread.currentThread().getContextClassLoader();
                }

            };

            proxyClass = SecurityUtil.doPrivilegedCreateClass(factory);
        }

        return proxyClass;
    }
    
    public  ProxyFactory createProxyFactory(Bean<?> bean) throws Exception
    {
        Set<Type> types = bean.getTypes();
        Set<Class<?>> interfaceList = new HashSet<Class<?>>();
        Class<?> superClass = null;
        for (Type generic : types)
        {
            Class<?> type = ClassUtil.getClazz(generic);
            
            if (type.isInterface())
            {
                interfaceList.add(type);
            }
            
            else if ((superClass == null) || (superClass.isAssignableFrom(type) && type != Object.class))
            {
                superClass = type;
            }

        }
        if (!interfaceList.contains(Serializable.class))
        {
            interfaceList.add(Serializable.class);
        }

        Class<?>[] interfaceArray = new Class<?>[interfaceList.size()];
        interfaceArray = interfaceList.toArray(interfaceArray);

        ProxyFactory fact = new ProxyFactory();        
        fact.setInterfaces(interfaceArray);
        fact.setSuperclass(superClass);        
        
        return fact;
        
    }
    
    public static WebBeansAnnotation createNewAnnotationProxy(Class<? extends Annotation> annotationType)
    {
        WebBeansAnnotation result = null;

        try
        {
            ProxyFactory pf = new ProxyFactory();
            pf.setInterfaces(new Class<?>[] { annotationType, Annotation.class });
            pf.setSuperclass(WebBeansAnnotation.class);

            result = (WebBeansAnnotation) pf.create(new Class[] { Class.class }, new Object[] { annotationType });
            ((ProxyObject)result).setHandler(new WebBeansAnnotation(annotationType));
        }
        catch (Exception e)
        {
            WebBeansUtil.throwRuntimeExceptions(e);
        }

        return result;
    }
    
    /**
     * @param o the object to check
     * @return <code>true</code> if the given object is a proxy
     */
    public static boolean isProxyInstance(Object o)
    {
        return o instanceof ProxyObject;
    }

}
