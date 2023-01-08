/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Helper class for use in bean factory implementations,
 * resolving values contained in bean definition objects
 * into the actual values applied to the target bean instance.
 *
 * <p>在bean工厂实现中使用Helper类，它将beanDefinition对象中包含的值解析为应用于
 * 目标bean实例的实际值</p>
 *
 * <p>Operates on an {@link AbstractBeanFactory} and a plain
 * {@link org.springframework.beans.factory.config.BeanDefinition} object.
 * Used by {@link AbstractAutowireCapableBeanFactory}.
 *
 * <p>在AbstractBeanFactory和纯BeanDefinition对象上操作。由AbstractAutowireCapableBeanFactory
 * 使用</p>
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @see AbstractAutowireCapableBeanFactory
 * @since 1.2
 */
public class BeanDefinitionValueResolver {

    /**
     * 当前Bean工厂
     */
    private final AbstractAutowireCapableBeanFactory beanFactory;

    /**
     * 要使用的bean名
     */
    private final String beanName;

    /**
     * beanName对应的beanDefinition
     */
    private final BeanDefinition beanDefinition;

    /**
     * 用于解析TypeStringValues的TypeConverter
     */
    private final TypeConverter typeConverter;


    /**
     * Create a BeanDefinitionValueResolver for the given BeanFactory and BeanDefinition.
     * <p>为给定BeanFactory和BeanDefinition创建一个BeanDefinitionValueResolver实例</p>
     *
     * @param beanFactory    the BeanFactory to resolve against -- 要解决的Bean工厂，即当前bean工厂
     * @param beanName       the name of the bean that we work on -- 我们要使用的bean名
     * @param beanDefinition the BeanDefinition of the bean that we work on -- 我们要使用的bean的BeanDefinition
     * @param typeConverter  the TypeConverter to use for resolving TypedStringValues
     *                       -- 用于解析TypeStringValues的TypeConverter
     */
    public BeanDefinitionValueResolver(AbstractAutowireCapableBeanFactory beanFactory, String beanName,
                                       BeanDefinition beanDefinition, TypeConverter typeConverter) {

        this.beanFactory = beanFactory;
        this.beanName = beanName;
        this.beanDefinition = beanDefinition;
        this.typeConverter = typeConverter;
    }

    /**
     * Create a BeanDefinitionValueResolver for the given BeanFactory and BeanDefinition
     * using a default {@link TypeConverter}.
     *
     * @param beanFactory    the BeanFactory to resolve against
     * @param beanName       the name of the bean that we work on
     * @param beanDefinition the BeanDefinition of the bean that we work on
     */
    public BeanDefinitionValueResolver(AbstractAutowireCapableBeanFactory beanFactory, String beanName,
                                       BeanDefinition beanDefinition) {

        this.beanFactory = beanFactory;
        this.beanName = beanName;
        this.beanDefinition = beanDefinition;
        BeanWrapper beanWrapper = new BeanWrapperImpl();
        beanFactory.initBeanWrapper(beanWrapper);
        this.typeConverter = beanWrapper;
    }


    /**
     * Given a PropertyValue, return a value, resolving any references to other
     * beans in the factory if necessary. The value could be:
     * <p>给定一个PropertyValue,返回一个值,如有必要,解析对工厂中其他bean的任何引用，
     * 该值可以是:</p>
     * <li>A BeanDefinition, which leads to the creation of a corresponding
     * new bean instance. Singleton flags and names of such "inner beans"
     * are always ignored: Inner beans are anonymous prototypes.
     * <li>BeanDefinition,它导致创建相应的新bean实例。此类"内部bean"的单例标志
     * 和名称始终被忽略：内部bean是匿名原型</li>
     * <li>A RuntimeBeanReference, which must be resolved.
     * <li>一个RuntimeBeanReference，必需解决</li>
     * <li>A ManagedList. This is a special collection that may contain
     * RuntimeBeanReferences or Collections that will need to be resolved.
     * <li>一个ManagedList.这是一个特殊的集合，其中可能包含需要解决的RuntimeBeanReferences或
     * Collections</li>
     * <li>A ManagedSet. May also contain RuntimeBeanReferences or
     * Collections that will need to be resolved.
     * <p>一个ManagedSet.也可能包含需要解决的RuntimeBeanReference或Collections</p>
     * <li>A ManagedMap. In this case the value may be a RuntimeBeanReference
     * or Collection that will need to be resolved.
     * <p>一个ManagedMap.在这种情况下，该值可能是需要解析的RuntimeBeanReference或
     * Collection。</p>
     * <li>An ordinary object or {@code null}, in which case it's left alone.
     * <p>普通对象或null,在这种情况下，将其保留</p>
     *
     * @param argName the name of the argument that the value is defined for
     *                -- 为其定义值得参数名称
     * @param value   the value object to resolve -- 要解决的对象值
     * @return the resolved object -- 解析的对象
     */
    @Nullable
    public Object resolveValueIfNecessary(Object argName, @Nullable Object value) {
        // We must check each value to see whether it requires a runtime reference
        // to another bean to be resolved.
        // 我们必需检查每个值，以查看它是否需要对另一个bean的运行时引用才能解决
        // RuntimeBeanReference:当属性值对象是工厂中另一个bean的引用时，使用不可变的占位符类，在运行时进行解析
        // 在下面这种配置就会生成RuntimeBeanReference:
        //   <bean class="foo.bar.xxx">
        //      <property name="referBeanName" ref="otherBeanName" />
        //   </bean>
        // 如果values是RuntimeBeanReference实例
        if (value instanceof RuntimeBeanReference ref) {
            //解析出对应ref所封装的Bean元信息(即Bean名,Bean类型)的Bean对象:
            return resolveReference(argName, ref);
        }
        //RuntimeBeanNameReference对应于<idref bean="bea" />.
        // idref注入的是目标bean的id而不是目标bean的实例，同时使用idref容器在部署的时候还会验证这个名称的bean
        // 是否真实存在。其实idref就跟value一样，只是将某个字符串注入到属性或者构造函数中，只不过注入的是某个
        // Bean定义的id属性值:
        // 即: <idref bean="bea" /> 等同于 <value>bea</value>
        // 参考博客：https://www.cnblogs.com/softidea/p/5815785.html  https://www.cnblogs.com/Mrfanl/p/9757780.html
        // 如果values是RuntimeBeanReference实例
        else if (value instanceof RuntimeBeanNameReference ref) {
            //从value中获取引用的Bean名
            String refName = ref.getBeanName();
            //对refName进行解析，然后重新赋值给refName
            refName = String.valueOf(doEvaluate(refName));
            //如果该bean工厂不包含具有refName的BeanDefinition或外部注册的singleton实例
            if (!this.beanFactory.containsBean(refName)) {
                //抛出BeanDefinition存储异常：argName的Bean引用中的Bean名'refName'无效
                throw new BeanDefinitionStoreException(
                        "Invalid bean name '" + refName + "' in bean reference for " + argName);
            }
            //返回经过解析且经过检查其是否存在于Bean工厂的引用Bean名
            return refName;
        }
        //BeanDefinitionHolder:具有名称和别名的bean定义的持有者，可以注册为内部bean的占位符
        // 出现BeanDefinitionHolder的情况，一般是内部Bean配置:https://www.cnblogs.com/shamo89/p/9917663.html
        //如果value是BeanDefinitionHolder实例
        else if (value instanceof BeanDefinitionHolder bdHolder) {
            // Resolve BeanDefinitionHolder: contains BeanDefinition with name and aliases.
            // 解决BeanDefinitionHolder：包含具有名称和别名的BenDefinition
            //根据bdHolder所封装的Bean名和BeanDefinition对象解析出内部Bean对象
            return resolveInnerBean(bdHolder.getBeanName(), bdHolder.getBeanDefinition(),
                    (name, mbd) -> resolveInnerBeanValue(argName, name, mbd));
        }
        //一般在内部匿名bean的配置才会出现BeanDefinition,如下：
        //<bean id="myStudent" class="com.hk.spring.di10.Student" autowire="byType">
        //        <property name="name" value="张三"/>
        //        <property name="age" value="9"/>
        //        <property name="school">
        //            <!-- 内部匿名bean -->
        //            <bean class="com.hk.spring.di10.School">
        //            	<property name="schloolName" value="红林小学"/>
        //            </bean>
        //        </property>
        //</bean>
        //如果value是BeanDefinition实例
        else if (value instanceof BeanDefinition bd) {
            //解析出内部Bean对象
            return resolveInnerBean(null, bd,
                    (name, mbd) -> resolveInnerBeanValue(argName, name, mbd));
        }
        //如果values是DependencyDescriptor实例
        else if (value instanceof DependencyDescriptor dependencyDescriptor) {
            //定义一个用于存放所找到的所有候选Bean名的集合，初始化长度为4
            Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
            //根据descriptor的依赖类型解析出与descriptor所包装的对象匹配的候选Bean对象
            Object result = this.beanFactory.resolveDependency(
                    dependencyDescriptor, this.beanName, autowiredBeanNames, this.typeConverter);
            //遍历autowiredBeanNames
            for (String autowiredBeanName : autowiredBeanNames) {
                //如果该bean工厂包含具有autowiredBeanName的beanDefinition或外部注册的singleton实例：
                if (this.beanFactory.containsBean(autowiredBeanName)) {
                    //注册autowiredBeanName与beanName的依赖关系
                    this.beanFactory.registerDependentBean(autowiredBeanName, this.beanName);
                }
            }
            //返回与descriptor所包装的对象匹配的候选Bean对象
            return result;
        }
        //一般在array标签才会出现ManagedArray,如下：
        //<property name="arrays">
        //     <array>
        //       <value>数据1</value>
        //       <value>数据2</value>
        //       <value>数据3</value>
        //     </array>
        //</property>
        //如果value是ManagedArray实例
        else if (value instanceof ManagedArray managedArray) {
            // May need to resolve contained runtime references.
            // 可能需要解析包含的运行时引用
            //获取array的已解析元素类型
            Class<?> elementType = managedArray.resolvedElementType;
            //如果elementType为null
            if (elementType == null) {
                //获取array的元素类型名，指array标签的value-type属性
                String elementTypeName = managedArray.getElementTypeName();
                //如果elementTypeName不是空字符串
                if (StringUtils.hasText(elementTypeName)) {
                    try {
                        //使用Bean工厂的Bean类型加载器加载elementTypeName对应的Class对象
                        elementType = ClassUtils.forName(elementTypeName, this.beanFactory.getBeanClassLoader());
                        //让array#resolvedElementType属性引用elementType
                        managedArray.resolvedElementType = elementType;
                    }
                    //捕捉加载elementTypeName对应的Class对象的所有异常
                    catch (Throwable ex) {
                        // Improve the message by showing the context.
                        // 通过显示上下问来改善消息
                        // 抛出Bean创建异常并引用ex:错误解析数组类型
                        throw new BeanCreationException(
                                this.beanDefinition.getResourceDescription(), this.beanName,
                                "Error resolving array type for " + argName, ex);
                    }
                } else {
                    //让elementType默认使用Object类对象
                    elementType = Object.class;
                }
            }
            //解析ManagedArray对象，以得到解析后的数组对象
            return resolveManagedArray(argName, (List<?>) value, elementType);
        }
        //一般在list标签才会出现ManagedList,如下：
        // <property name="list">
        //    <list>
        //     <value>123</value>
        //     <value>ABC</value>
        //     <value>数据String</value>
        //    </list>
        // </property>
        //如果value是ManagedArray实例
        else if (value instanceof ManagedList<?> managedList) {
            // May need to resolve contained runtime references.
            // 可能需要解析包含的运行时引用
            //解析ManagedList对象，以得到解析后的List对象并结果返回出去
            return resolveManagedList(argName, managedList);
        }
        //一般在set标签才会出现ManagedList,如下：
        //<set>
        // <ref bean="otherBean" />
        // <ref bean="otherBean" />
        // <bean class="com.zhengqing._03_di.OtherBean" />
        // <bean class="com.zhengqing._03_di.OtherBean" />
        //</set>
        //如果value是ManagedSet对象
        else if (value instanceof ManagedSet<?> managedSet) {
            // May need to resolve contained runtime references.
            // 可能需要解析包含的运行时引用
            //解析ManagedSet对象，以得到解析后的Set对象并结果返回出去
            return resolveManagedSet(argName, managedSet);
        }
        //一般在map标签才会出现ManagedMap,如下
        //<map>
        //	<entry key="1" value-ref="user1"></entry>
        //	<entry key="2" value-ref="user2"></entry>
        //</map>
        //如果value是ManagedMap对象
        else if (value instanceof ManagedMap<?, ?> managedMap) {
            // May need to resolve contained runtime references.
            //可能需要解析包含的运行时引用
            // 解析ManagedMap对象，以得到解析后的Map对象并结果返回出去
            return resolveManagedMap(argName, managedMap);
        }
        //一般props标签才会出现ManagedProperties,如下：
        //<props>
        //	<prop key="setA*">PROPAGATION_REQUIRED</prop>
        //	<prop key="rollbackOnly">PROPAGATION_REQUIRED</prop>
        //	<prop key="echoException">PROPAGATION_REQUIRED,+javax.servlet.ServletException,-java.lang.Exception</prop>
        //</props>
        //如果value是ManagedProperties对象
        else if (value instanceof ManagedProperties original) {
            // Properties original = managedProperties;
            //定义一个用于存储将original的所有Property的键/值解析后的键/值的Properties对象
            Properties copy = new Properties();
            //遍历original，键名为propKey,值为propValue
            original.forEach((propKey, propValue) -> {
                //如果proKey是TypeStringValue实例
                if (propKey instanceof TypedStringValue typedStringValue) {
                    //在propKey封装的value可解析成表达式的情况下,将propKey封装的value评估为表达式并解析出表达式的值
                    propKey = evaluate(typedStringValue);
                }
                //如果proValue式TypeStringValue实例
                if (propValue instanceof TypedStringValue typedStringValue) {
                    //在propValue封装的value可解析成表达式的情况下,将propValue封装的value评估为表达式并解析出表达式的值
                    propValue = evaluate(typedStringValue);
                }
                //如果proKey或者propValue为null
                if (propKey == null || propValue == null) {
                    //抛出Bean创建异常:转换argName的属性键/值时出错：解析为null
                    throw new BeanCreationException(
                            this.beanDefinition.getResourceDescription(), this.beanName,
                            "Error converting Properties key/value pair for " + argName + ": resolved to null");
                }
                //将propKey和propValue添加到copy中
                copy.put(propKey, propValue);
            });
            return copy;
        }
        //如果value时TypeStringValue实例
        else if (value instanceof TypedStringValue typedStringValue) {
            // Convert value to target type here.
            // 在此处将值转换为目标类型
            Object valueObject = evaluate(typedStringValue);
            try {
                //在typedStringValue中解析目标类型
                Class<?> resolvedTargetType = resolveTargetType(typedStringValue);
                //如果resolvedTargetType不为null
                if (resolvedTargetType != null) {
                    //使用typeConverter将值转换为所需的类型
                    return this.typeConverter.convertIfNecessary(valueObject, resolvedTargetType);
                } else {
                    //返回并解析出来表达式的值
                    return valueObject;
                }
            }
            //捕捉在解析目标类型或转换类型过程中抛出的异常
            catch (Throwable ex) {
                // Improve the message by showing the context.
                // 通过显示上下文来改善消息
                // 抛出Bean创建异常：为argName转换键入的字符串值时错误
                throw new BeanCreationException(
                        this.beanDefinition.getResourceDescription(), this.beanName,
                        "Error converting typed String value for " + argName, ex);
            }
        }
        //如果value时NullBean实例
        else if (value instanceof NullBean) {
            //直接返回null
            return null;
        } else {
            //对于value是String/String[]类型会尝试评估为表达式并解析出表达式的值，其他类型直接返回value.
            return evaluate(value);
        }
    }

    /**
     * Resolve an inner bean definition and invoke the specified {@code resolver}
     * on its merged bean definition.
     *
     * @param innerBeanName the inner bean name (or {@code null} to assign one)
     * @param innerBd       the inner raw bean definition
     * @param resolver      the function to invoke to resolve
     * @param <T>           the type of the resolution
     * @return a resolved inner bean, as a result of applying the {@code resolver}
     * @since 6.0
     */
    public <T> T resolveInnerBean(@Nullable String innerBeanName, BeanDefinition innerBd,
                                  BiFunction<String, RootBeanDefinition, T> resolver) {

        String nameToUse = (innerBeanName != null ? innerBeanName : "(inner bean)" +
                BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR + ObjectUtils.getIdentityHexString(innerBd));
        return resolver.apply(nameToUse,
                this.beanFactory.getMergedBeanDefinition(nameToUse, innerBd, this.beanDefinition));
    }

    /**
     * Evaluate the given value as an expression, if necessary.
     * <p>如有必要，将给定值作为表达式求值</p>
     *
     * @param value the candidate value (may be an expression) -- 候选值(可以是表达式)
     * @return the resolved value -- 解析值
     */
    @Nullable
    protected Object evaluate(TypedStringValue value) {
        //如有必要(value可解析成表达式的情况下)，将value封装的value评估为表达式并解析出表达式的值
        Object result = doEvaluate(value.getValue());
        //如果result与value所封装的value不相等
        if (!ObjectUtils.nullSafeEquals(result, value.getValue())) {
            //将value标记为动态，即包含一个表达式，因此不进行缓存
            value.setDynamic();
        }
        //返回result
        return result;
    }

    /**
     * Evaluate the given value as an expression, if necessary.
     * <p>如有必要，将给定值作为表达式求值</p>
     *
     * @param value the original value (may be an expression)  -- 原始(可以是表达式)
     * @return the resolved value if necessary, or the original value
     * -- 必要时解析值或原始值
     */
    @Nullable
    protected Object evaluate(@Nullable Object value) {
        //如果value是String对象
        if (value instanceof String str) {
            //如有必要(value可解析成表达式的情况下)，将value评估为表达式并解析出表达式的值并返回出去
            return doEvaluate(str);
        }
        //如果value是String数组对象
        else if (value instanceof String[] values) {
            //是否经过解析的标记，默认为false
            boolean actuallyResolved = false;
            //定义用于存放解析的值的Object数组，长度为values的长度
            Object[] resolvedValues = new Object[values.length];
            //遍历values(以fori形式)
            for (int i = 0; i < values.length; i++) {
                //获取第i个values元素
                String originalValue = values[i];
                //如有必要(value可解析成表达式的情况下)，将originalValue评估为表达式并解析出表达式的值
                Object resolvedValue = doEvaluate(originalValue);
                //如果resolvedValue与originalValue不是同一个对象
                if (resolvedValue != originalValue) {
                    //经过解析标记为true，表示已经过解析
                    actuallyResolved = true;
                }
                //将resolvedValue赋值第i个resolvedValues元素中
                resolvedValues[i] = resolvedValue;
            }
            //如果已经过解析，返回解析后的数组【resovledValues】；否则返回values
            return (actuallyResolved ? resolvedValues : values);
        } else {
            //其他类型直接返回value
            return value;
        }
    }

    /**
     * Evaluate the given String value as an expression, if necessary.
     * <p>如有必要(value可解析成表达式的情况下)，将给定的String值评估为表达式并解析出表达式的值</p>
     *
     * @param value the original value (may be an expression)
     *              -- 原始值(可能式一个表达式)
     * @return the resolved value if necessary, or the original String value
     * -- 必要时解析的值或原始String值
     */
    @Nullable
    private Object doEvaluate(@Nullable String value) {
        //评估value,如果value是可解析表达式，会对其进行解析，否则直接返回value
        return this.beanFactory.evaluateBeanDefinitionString(value, this.beanDefinition);
    }

    /**
     * Resolve the target type in the given TypedStringValue.
     * <p>在给定的TypedStringValue中解析目标类型</p>
     *
     * @param value the TypedStringValue to resolve -- 要解析的TypedStringValue
     * @return the resolved target type (or {@code null} if none specified)
     * -- 解析的目标类型(如果未指定，则未null)
     * @throws ClassNotFoundException if the specified type cannot be resolved
     *                                -- 如果无法解析指定的类型
     * @see TypedStringValue#resolveTargetType
     */
    @Nullable
    protected Class<?> resolveTargetType(TypedStringValue value) throws ClassNotFoundException {
        //如果value有携带目标类型
        if (value.hasTargetType()) {
            //返回value的目标类型
            return value.getTargetType();
        }
        //从value中解析出目标类型
        return value.resolveTargetType(this.beanFactory.getBeanClassLoader());
    }

    /**
     * Resolve a reference to another bean in the factory.
     * <p>在工厂中解决对另一个bean的引用</p>
     *
     * @param argName 为其定义值得参数名称
     * @param ref     封装者另一个bean的引用的RuntimeBeanReference对象
     */
    @Nullable
    private Object resolveReference(Object argName, RuntimeBeanReference ref) {
        try {
            //定义用于一个存储bean对象的变量
            Object bean;
            //获取另一个Bean引用的Bean类型
            Class<?> beanType = ref.getBeanType();
            //如果引用来自父工厂
            if (ref.isToParent()) {
                //获取父工厂
                BeanFactory parent = this.beanFactory.getParentBeanFactory();
                //如果没有父工厂
                if (parent == null) {
                    //抛出Bean创建异常：无法解析对bean的引用 ref 在父工厂中:没有可以的父工厂
                    throw new BeanCreationException(
                            this.beanDefinition.getResourceDescription(), this.beanName,
                            "Cannot resolve reference to bean " + ref +
                                    " in parent factory: no parent factory available");
                }
                //如果引用的Bean类型不为null
                if (beanType != null) {
                    //从父工厂中获取引用的Bean类型对应的Bean对象
                    bean = parent.getBean(beanType);
                } else {
                    //否则,使用引用的Bean名,从父工厂中获取对应的Bean对像
                    bean = parent.getBean(String.valueOf(doEvaluate(ref.getBeanName())));
                }
            } else {
                //定义一个用于存储解析出来的Bean名的变量
                String resolvedName;
                //如果beanType不为null
                if (beanType != null) {
                    //解析与beanType唯一匹配的bean实例，包括其bean名
                    NamedBeanHolder<?> namedBean = this.beanFactory.resolveNamedBean(beanType);
                    //让bean引用nameBean所封装的Bean对象
                    bean = namedBean.getBeanInstance();
                    //让resolvedName引用nameBean所封装的Bean名
                    resolvedName = namedBean.getBeanName();
                } else {
                    //让resolvedName引用ref所包装的Bean名
                    resolvedName = String.valueOf(doEvaluate(ref.getBeanName()));
                    //获取resolvedName的Bean对象
                    bean = this.beanFactory.getBean(resolvedName);
                }
                //注册beanName与dependentBeanNamed的依赖关系到Bean工厂
                this.beanFactory.registerDependentBean(resolvedName, this.beanName);
            }
            //如果Bean对象是NullBean实例
            if (bean instanceof NullBean) {
                //将bean置为null
                bean = null;
            }
            //返回解析出来对应ref所封装的Bean元信息(即Bean名,Bean类型)的Bean对象
            return bean;
        }
        //捕捉Bean包和子包中引发的所有异常
        catch (BeansException ex) {
            //抛出Bean创建异常，包装ex：设置argName时无法解析对bean'ref.getBeanName()'的引用
            throw new BeanCreationException(
                    this.beanDefinition.getResourceDescription(), this.beanName,
                    "Cannot resolve reference to bean '" + ref.getBeanName() + "' while setting " + argName, ex);
        }
    }

    /**
     * Resolve an inner bean definition.
     * <p>解析内部BeanDefinition</p>
     *
     * @param argName       the name of the argument that the inner bean is defined for
     *                      -- 为其定义内部Bean的参数名，即外层Bean的属性名
     * @param innerBeanName the name of the inner bean
     *                      -- 内部Bean名
     * @param mbd           the merged bean definition for the inner bean
     *                      -- 内部Bean的BeanDefinition
     * @return the resolved inner bean instance   -- 解析的内部Bean实例
     */
    @Nullable
    private Object resolveInnerBeanValue(Object argName, String innerBeanName, RootBeanDefinition mbd) {
        try {
            // Check given bean name whether it is unique. If not already unique,
            // add counter - increasing the counter until the name is unique.
            // 检查给定的Bean名是否唯一。如果还不是唯一的,添加计数器-增加计数器,直到名称唯一为止.
            //解决内部Bean名需要唯一的问题
            //定义实际的内部Bean名,初始为innerBeanName
            String actualInnerBeanName = innerBeanName;
            //如果mbd配置成了单例
            if (mbd.isSingleton()) {
                //调整innerBeanName,直到该Bean名在工厂中唯一。最后将结果赋值给actualInnerBeanName
                actualInnerBeanName = adaptInnerBeanName(innerBeanName);
            }
            //将actualInnerBeanName和beanName的包含关系注册到该工厂中
            this.beanFactory.registerContainedBean(actualInnerBeanName, this.beanName);
            // Guarantee initialization of beans that the inner bean depends on.
            // 确保内部Bean依赖的Bean的初始化
            //获取mdb的要依赖的Bean名
            String[] dependsOn = mbd.getDependsOn();
            //如果有需要依赖的Bean名
            if (dependsOn != null) {
                //遍历depensOn
                for (String dependsOnBean : dependsOn) {
                    //注册dependsOnBean与actualInnerBeanName的依赖关系到该工厂中
                    this.beanFactory.registerDependentBean(dependsOnBean, actualInnerBeanName);
                    //获取dependsOnBean的Bean对像(不引用，只是为了让dependsOnBean所对应的Bean对象实例化)
                    this.beanFactory.getBean(dependsOnBean);
                }
            }
            // Actually create the inner bean instance now...
            // 实际上现有创建内部bean实例
            // 创建actualInnerBeanName的Bean对象
            Object innerBean = this.beanFactory.createBean(actualInnerBeanName, mbd, null);
            //如果innerBean时FactoryBean的实例
            if (innerBean instanceof FactoryBean<?> factoryBean) {
                //mbds是否是"synthetic"的标记。一般是指只有AOP相关的prointCut配置或者Advice配置才会将 synthetic设置为true
                boolean synthetic = mbd.isSynthetic();
                //从BeanFactory对象中获取管理的对象，只有mbd不是synthetic才对其对象进行该工厂的后置处理
                innerBean = this.beanFactory.getObjectFromFactoryBean(factoryBean, actualInnerBeanName, !synthetic);
            }
            //如果innerBean是NullBean实例
            if (innerBean instanceof NullBean) {
                //将innerBean设置为null
                innerBean = null;
            }
            //返回actualInnerBeanName的Bean对象
            return innerBean;
        }
        //捕捉解析内部Bean对象过程中抛出的Bean包和子包中引发的所有异常
        catch (BeansException ex) {
            //抛出Bean创建异常，引用ex：
            // 在mdb不为null且mdb的Bean类名不为null的情况下，描述异常：无法创建类为[mdb的Bean类型名]的内部bean 'innerBeanName'
            // 否则，描述异常：设置argName时无法创建内部bean 'innerBeanName'
            throw new BeanCreationException(
                    this.beanDefinition.getResourceDescription(), this.beanName,
                    "Cannot create inner bean '" + innerBeanName + "' " +
                            (mbd.getBeanClassName() != null ? "of type [" + mbd.getBeanClassName() + "] " : "") +
                            "while setting " + argName, ex);
        }
    }

    /**
     * Checks the given bean name whether it is unique. If not already unique,
     * a counter is added, increasing the counter until the name is unique.
     * <p>检查给定Bean名是否唯一.如果还不是唯一的,则添加该计数器,直到名称唯一位置</p>
     *
     * @param innerBeanName the original name for the inner bean
     *                      -- 内部Bean的原始名称
     * @return the adapted name for the inner bean
     * -- 内部Bean的调整后的最终Bean名
     */
    private String adaptInnerBeanName(String innerBeanName) {
        //定义一个实际内部Bean名变量，初始为innerBean名
        String actualInnerBeanName = innerBeanName;
        //定义一个用于计数的计数器，初始为0
        int counter = 0;
        //只要actualInnerBeanName是否已在该工厂中使用就继续循环,即actualInnerBeanName是否是别名
        // 或该工厂是否已包含actualInnerBeanName的bean对象 或 该工厂是否已经为actualInnerBeanName注册了依赖Bean关系
        while (this.beanFactory.isBeanNameInUse(actualInnerBeanName)) {
            //计数器+1
            counter++;
            //让actualInnerBeanName重新引用拼接后的字符串:innerBeanName+'#'+count
            actualInnerBeanName = innerBeanName + BeanFactoryUtils.GENERATED_BEAN_NAME_SEPARATOR + counter;
        }
        //返回经过调整后的Bean名
        return actualInnerBeanName;
    }

    /**
     * <p>解析ManagedArray对象，以得到解析后的数组对象:
     *  <ol>
     *   <li>创建一个用于存放解析后的实例对象的elementType类型长度为ml大小的数组【变量 resolved】</li>
     *   <li>遍历ml(以fori形式):获取第i个ml元素对象，解析该元素对象的实例对象然后设置到第i个
     *   resolved元素中</li>
     *   <li>返回解析后的的数组对象【resolved】</li>
     *  </ol>
     * </p>
     * For each element in the managed array, resolve reference if necessary.
     * <p>对于托管数组中的每个元素，如有必要，请解析引用</p>
     */
    private Object resolveManagedArray(Object argName, List<?> ml, Class<?> elementType) {
        //创建一个用于存放解析后的实例对象的elementType类型长度为ml大小的数组
        Object resolved = Array.newInstance(elementType, ml.size());
        //遍历ml(以fori形式)
        for (int i = 0; i < ml.size(); i++) {
            //获取第i个ml元素对象，解析出该元素对象的实例对象然后设置到第i个resolved元素中
            Array.set(resolved, i, resolveValueIfNecessary(new KeyedArgName(argName, i), ml.get(i)));
        }
        //返回解析后的的数组对象【resolved】
        return resolved;
    }

    /**
     * <p>解析ManagedList对象，以得到解析后的List对象:
     *  <ol>
     *   <li>定义一个用于存放解析后的实例对象的ArrayList，初始容量为ml大小【变量 resolved】</li>
     *   <li>遍历ml(以fori形式)，获取第i个ml元素对象，解析该元素对象的实例对象然后添加到
     *   resolved中</li>
     *   <li>返回【resolved】</li>
     *  </ol>
     * </p>
     * For each element in the managed list, resolve reference if necessary.
     * <p>对于托管列表中的每个元素，如有必要，请解析引用</p>
     */
    private List<?> resolveManagedList(Object argName, List<?> ml) {
        //定义一个用于存放解析后的实例对象的ArrayList，初始容量为ml大小
        List<Object> resolved = new ArrayList<>(ml.size());
        //遍历ml(以fori形式)
        for (int i = 0; i < ml.size(); i++) {
            //获取第i个ml元素对象，解析出该元素对象的实例对象然后添加到resolved中
            resolved.add(resolveValueIfNecessary(new KeyedArgName(argName, i), ml.get(i)));
        }
        //返回【resolved】
        return resolved;
    }


    /**
     * <p>解析ManagedSet对象，以得到解析后的Set对象:
     *  <ol>
     *   <li>定义一个用于存放解析后的实例对象的LinkedHashSet，初始容量为ml大小【变量 resolved】</li>
     *   <li>定义一个遍历时的偏移量【变量 i】</li>
     *   <li>遍历ms，元素为m:
     *    <ol>
     *     <li>解析出该m的实例对象然后添加到resolved中</li>
     *     <li>偏移量+1【i】</li>
     *    </ol>
     *   </li>
     *   <li>返回resolved</li>
     *  </ol>
     * </p>
     * For each element in the managed set, resolve reference if necessary.
     * <p>对于托管集中的每个元素，如有必要，请解析引用</p>
     */
    private Set<?> resolveManagedSet(Object argName, Set<?> ms) {
        //定义一个用于存放解析后的实例对象的LinkedHashSet，初始容量为ml大小
        Set<Object> resolved = new LinkedHashSet<>(ms.size());
        //定义一个遍历时的偏移量i
        int i = 0;
        //遍历ms，元素为m
        for (Object m : ms) {
            //解析出该m的实例对象然后添加到resolved中
            resolved.add(resolveValueIfNecessary(new KeyedArgName(argName, i), m));
            //偏移量+1
            i++;
        }
        //返回resolved
        return resolved;
    }

    /**
     * <p>解析ManagedMap对象，以得到解析后的Map对象:
     *  <ol>
     *   <li>定义用于存储解析后的key实例对象和value实例对象的LinkedHashMap,长度为mm的大小【变量 resolved】</li>
     *   <li>遍历mm，键名为key,值为value:
     *    <ol>
     *     <li>解析mm的key的实例对象【变量 resolvedKey】</li>
     *     <li>解析mm的value的实例对象【变量 resolvedValue】</li>
     *     <li>将解析出来的key和value的实例对象添加到resolved中</li>
     *    </ol>
     *   </li>
     *   <li>返回resolved</li>
     *  </ol>
     * </p>
     * For each element in the managed map, resolve reference if necessary.
     * <p>对于托管映射中的每个元素，如有必要，请解析引用</p>
     */
    private Map<?, ?> resolveManagedMap(Object argName, Map<?, ?> mm) {
        //定义用于存储解析后的key实例对象和value实例对象的LinkedHashMap,长度为mm的大小
        Map<Object, Object> resolved = new LinkedHashMap<>(mm.size());
        //遍历mm
        mm.forEach((key, value) -> {
            //解析mm的key的实例对象
            Object resolvedKey = resolveValueIfNecessary(argName, key);
            //解析mm的value的实例对象
            Object resolvedValue = resolveValueIfNecessary(new KeyedArgName(argName, key), value);
            //将解析出来的key和value的实例对象添加到resolved中
            resolved.put(resolvedKey, resolvedValue);
        });
        //返回resolved
        return resolved;
    }


    /**
     * Holder class used for delayed toString building.
     * <p>用于延迟toString构建的Holder类</p>
     */
    private static class KeyedArgName {

        /**
         * 参数名
         */
        private final Object argName;

        /**
         * 键值
         */
        private final Object key;

        /**
         * 新建一个KeyedArgName对象
         *
         * @param argName 参数名
         * @param key     键值
         */
        public KeyedArgName(Object argName, Object key) {
            this.argName = argName;
            this.key = key;
        }

        @Override
        public String toString() {
            return this.argName + " with key " + BeanWrapper.PROPERTY_KEY_PREFIX +
                    this.key + BeanWrapper.PROPERTY_KEY_SUFFIX;
        }
    }

}
