/*
 * Copyright 2002-2023 the original author or authors.
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

import org.apache.commons.logging.Log;
import org.springframework.beans.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.core.*;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;
import org.springframework.util.function.ThrowingSupplier;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 * <p>实现缺省bean创建的抽象bean工厂超类，具有由RootBeanDefinition类指定的全部功能.
 * 除了AbstractBeanFactory的createBean方法之外，还实现了AutowireCapableBeanFactory接口.</p>
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 * <p>提供bean创建(带有构造函数解析)、属性填充、连接(包括自动装配)和初始化。处理运行时bean引用、
 * 解析托管集合、调用初始化方法等。支持自动装配构造函数，按名称和按类型的属性。</p>
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 * <p>由子类实现的主要模板方法是resolveDependency(DependencyDescriptor, String, Set, TypeConverter),
 * 用于按类型自动装配。对于能够搜索其beanDefinition的工厂，匹配bean通常是通过这样的搜索实现的。对于其他工厂样式，
 * 可以实现简化的匹配算法。</p>
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 * <p>注意，这个类没有假设或实现bean定义注册表功能.有关org.springframework.beans.factory.ListableBeanFactory
 * 和 BeanDefinitionRegistry 的实现请查阅DefaultListableBeanFactory,分别表示此类工厂的API和SPI视图。</p>
 * obtainFromSupplier
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 * @since 13.02.2004
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
		implements AutowireCapableBeanFactory {

	/**
	 * Strategy for creating bean instances.
	 * 创建Bean实例的策略
	 */
	private InstantiationStrategy instantiationStrategy;

	/**
	 * Resolver strategy for method parameter names.
	 * <p>方法参数的解析策略
	 */
	@Nullable
	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	/**
	 * Whether to automatically try to resolve circular references between beans.
	 * <p>是否尝试自动解决Bean之间的循环引用。</p>
	 * <p>循环引用的意思是：A引用了B,B引用了C,C又引用了A</p>
	 */
	private boolean allowCircularReferences = true;

	/**
	 * Whether to automatically try to resolve circular references between beans.
	 * <p> 在循环引用的情况下，是否借助于注入原始Bean实例，即使注入的Bean最终被包装
	 */
	private boolean allowRawInjectionDespiteWrapping = false;

	/**
	 * Dependency types to ignore on dependency check and autowire, as Set of
	 * Class objects: for example, String. Default is none.
	 * <p>依赖项检查和自动装配时要忽略的依赖类型，如一组 class 对象；例如，String.默认时没有的
	 */
	private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

	/**
	 * Dependency interfaces to ignore on dependency check and autowire, as Set of
	 * Class objects. By default, only the BeanFactory interface is ignored.
	 * <p>依赖接口忽略依赖检查和自动装配，作为类对象的集合。默认情况下，仅BeanFactory接口被忽略
	 */
	private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

	/**
	 * The name of the currently created bean, for implicit dependency registration
	 * on getBean etc invocations triggered from a user-specified Supplier callback.
	 *
	 * <p>当前创建的bean名称，用于从用户指定的Supplier回调触发的对getBean等调用的隐式依赖项注册
	 */
	private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

	/**
	 * Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper.
	 * <p>未完成的FactoryBean实例的高速缓存：FactoryBean名-BeanWrapper
	 */
	private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

	/**
	 * Cache of candidate factory methods per factory class.
	 * <p>每个工厂类的缓存候选工厂方法
	 */
	private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

	/**
	 * Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array.
	 * <p>过滤后的PropertyDescriptor的缓存：bean类到PropertyDescriptors数组
	 */
	private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
			new ConcurrentHashMap<>();


	/**
	 * Create a new AbstractAutowireCapableBeanFactory.
	 * <p>创建一个新的AbstractAutowireCapableBeanFactory
	 */
	public AbstractAutowireCapableBeanFactory() {
		super();
		ignoreDependencyInterface(BeanNameAware.class);
		ignoreDependencyInterface(BeanFactoryAware.class);
		ignoreDependencyInterface(BeanClassLoaderAware.class);
		this.instantiationStrategy = new CglibSubclassingInstantiationStrategy();
	}

	/**
	 * Create a new AbstractAutowireCapableBeanFactory with the given parent.
	 * <p>使用给定的父级Bean工厂创建一个新的AbstractAutowireCapableBeanFactory
	 *
	 * @param parentBeanFactory parent bean factory, or {@code null} if none 父级bean工厂，如果没有可以为 null
	 */
	public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this();
		//设置此bean工厂的父级,如果此工厂有父级bean工厂了会抛出IllegalStateException
		setParentBeanFactory(parentBeanFactory);
	}


	/**
	 * Set the instantiation strategy to use for creating bean instances.
	 * Default is CglibSubclassingInstantiationStrategy.
	 *
	 * @see CglibSubclassingInstantiationStrategy
	 */
	public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
		this.instantiationStrategy = instantiationStrategy;
	}

	/**
	 * Return the instantiation strategy to use for creating bean instances.
	 * 返回用于创建Bean实例的实例化策略
	 */
	public InstantiationStrategy getInstantiationStrategy() {
		return this.instantiationStrategy;
	}

	/**
	 * Set the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed (e.g. for constructor names).
	 * <p>如果需要，返回ParameterNameDiscover来解析方法参数名称
	 * <p>Default is a {@link DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Return the ParameterNameDiscoverer to use for resolving method parameter
	 * names if needed.
	 */
	@Nullable
	public ParameterNameDiscoverer getParameterNameDiscoverer() {
		return this.parameterNameDiscoverer;
	}

	/**
	 * Set whether to allow circular references between beans - and automatically
	 * try to resolve them.
	 * <p>设置是否在bean之间允许循环引用-并自动尝试解决它们
	 * <p>Note that circular reference resolution means that one of the involved beans
	 * will receive a reference to another bean that is not fully initialized yet.
	 * This can lead to subtle and not-so-subtle side effects on initialization;
	 * it does work fine for many scenarios, though.
	 * <p>注意，循环引用解析意味着其中一个涉及的bean将收到对另一个尚未完全初始化的bean引用。
	 * 者可能会导致初始化方面。这可能会导致初始化方面的细微和不太细微的副作用；不过，它在
	 * 许多情况下都可以正常工厂。
	 * <p>Default is "true". Turn this off to throw an exception when encountering
	 * a circular reference, disallowing them completely.
	 * <p>默认值为true,遇到循环引用是，请关闭此选项以引发异常，从而完全禁止它们。
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans. Refactor your application logic to have the two beans
	 * involved delegate to a third bean that encapsulates their common logic.
	 * <p>注意：通常建议不要在bean之间循环引用。重构您的应用程序逻辑，以使涉及的两个bean
	 * 委托给封装了它们公共逻辑的第三bean
	 */
	public void setAllowCircularReferences(boolean allowCircularReferences) {
		this.allowCircularReferences = allowCircularReferences;
	}

	/**
	 * Return whether to allow circular references between beans.
	 *
	 * @see #setAllowCircularReferences
	 * @since 5.3.10
	 */
	public boolean isAllowCircularReferences() {
		return this.allowCircularReferences;
	}

	/**
	 * Set whether to allow the raw injection of a bean instance into some other
	 * bean's property, despite the injected bean eventually getting wrapped
	 * (for example, through AOP auto-proxying).
	 * <p>设置是否允许将一个Bean实例原始注入到其他Bean的属性中，尽管注入的Bean最终被包装
	 * （例如，通过AOP自动代理）
	 * <p>This will only be used as a last resort in case of a circular reference
	 * that cannot be resolved otherwise: essentially, preferring a raw instance
	 * getting injected over a failure of the entire bean wiring process.
	 * <p>这只会循环引用无法通过其他方式解决的情况下作为最后的手段使用：本质上，在整个bean连接过程失败的情况下，宁愿注入一个原始实例
	 * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
	 * raw beans injected into some of your references, which was Spring 1.2's
	 * (arguably unclean) default behavior.
	 * <p>从Spring2.0开始，默认值是false。打开这个选项，允许将未包装的原始Bean注入到一些引用
	 * 中，这是Spring1.2的默认行为(可以说是不干净)
	 * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
	 * between your beans, in particular with auto-proxying involved.
	 * <p>注意：通常建议不要依赖与Bean之间的循环引用，特别是涉及到自动代理时。
	 *
	 * @see #setAllowCircularReferences
	 */
	public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
		this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
	}

	/**
	 * Return whether to allow the raw injection of a bean instance.
	 *
	 * @see #setAllowRawInjectionDespiteWrapping
	 * @since 5.3.10
	 */
	public boolean isAllowRawInjectionDespiteWrapping() {
		return this.allowRawInjectionDespiteWrapping;
	}

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 */
	public void ignoreDependencyType(Class<?> type) {
		this.ignoredDependencyTypes.add(type);
	}

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p> 忽略给定的依赖接口进行自动装配
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>应用程序上下文通常会使用它来注册依赖关系解决其他方式，例如BeanFactory通
	 * 过BeanFactoryAware或者ApplicationContext通过ApplicationContextAware注册</p>
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * <p>默认情况下，仅BeanFactoryAware接口被忽略。要忽略其他类型，请忽略其他类型，请
	 * 为每钟类型调用此方法。</p>
	 *
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 */
	public void ignoreDependencyInterface(Class<?> ifc) {
		//将要忽略的类添加到 忽略依赖类对象集合中
		this.ignoredDependencyInterfaces.add(ifc);
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof AbstractAutowireCapableBeanFactory otherAutowireFactory) {
			this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
			this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
			this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
			this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
		}
	}


	//-------------------------------------------------------------------------
	// Typical methods for creating and populating external bean instances
	//-------------------------------------------------------------------------

	@Override
	@SuppressWarnings("unchecked")
	public <T> T createBean(Class<T> beanClass) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass);
		bd.setAutowireMode(AUTOWIRE_CONSTRUCTOR);
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(beanClass, getBeanClassLoader());
		return (T) createBean(beanClass.getName(), bd, null);
	}

	@Override
	public void autowireBean(Object existingBean) {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
		bd.setScope(SCOPE_PROTOTYPE);
		bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), getBeanClassLoader());
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public Object configureBean(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition mbd = getMergedBeanDefinition(beanName);
		RootBeanDefinition bd = null;
		if (mbd instanceof RootBeanDefinition rbd) {
			bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
		}
		if (bd == null) {
			bd = new RootBeanDefinition(mbd);
		}
		if (!bd.isPrototype()) {
			bd.setScope(SCOPE_PROTOTYPE);
			bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), getBeanClassLoader());
		}
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(beanName, bd, bw);
		return initializeBean(beanName, existingBean, bd);
	}


	//-------------------------------------------------------------------------
	// Specialized methods for fine-grained control over the bean lifecycle
	//-------------------------------------------------------------------------

	@Override
	public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		return createBean(beanClass.getName(), bd, null);
	}

	@Override
	public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
			return autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
		} else {
			Object bean = getInstantiationStrategy().instantiate(bd, null, this);
			populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
			return bean;
		}
	}

	@Override
	public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
			throws BeansException {

		if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
			throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
		}
		// Use non-singleton bean definition, to avoid registering bean as dependent bean.
		RootBeanDefinition bd =
				new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
		bd.setScope(SCOPE_PROTOTYPE);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		populateBean(bd.getBeanClass().getName(), bd, bw);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
		markBeanAsCreated(beanName);
		BeanDefinition bd = getMergedBeanDefinition(beanName);
		BeanWrapper bw = new BeanWrapperImpl(existingBean);
		initBeanWrapper(bw);
		applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		return initializeBean(beanName, existingBean, null);
	}

	/**
	 * init-method调用之前回调所有BeanPostProcessor的postProcessBeforeInitialization方法
	 * 也就是@PostConstruct注解标注的初始化方法，在applyMergedBeanDefinitionPostProcessors方法中已经解析了该注解
	 *
	 * @param existingBean bean实例
	 * @param beanName     beanName
	 * @return 应用之后的返回值
	 */
	@Override
	public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
			throws BeansException {
		//最终返回的结果
		Object result = existingBean;
		//遍历所有已注册的BeanPostProcessor，按照遍历顺序回调postProcessBeforeInitialization方法
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			Object current = processor.postProcessBeforeInitialization(result, beanName);
			//如果途中某个processor的postProcessBeforeInitialization方法返回null，那么不进行后续的回调
			//直接返回倒数第二个processor的postProcessBeforeInitialization方法的返回值
			if (current == null) {
				return result;
			}
			//改变result指向当前的返回值
			result = current;
		}
		//返回result
		return result;
	}

	/**
	 * bean实例化之后应用所有已注册的BeanPostProcessor后处理器的postProcessBeforeInstantiation方法
	 *
	 * @param existingBean the existing bean instance
	 * @param beanName     the name of the bean, to be passed to it if necessary
	 *                     (only passed to {@link BeanPostProcessor BeanPostProcessors};
	 *                     can follow the {@link #ORIGINAL_INSTANCE_SUFFIX} convention in order to
	 *                     enforce the given instance to be returned, i.e. no proxies etc)
	 * @return
	 * @throws BeansException
	 */
	@Override
	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;
		//遍历所有已注册的BeanPostProcessor，按照遍历顺序回调postProcessAfterInitialization方法
		for (BeanPostProcessor processor : getBeanPostProcessors()) {
			Object current = processor.postProcessAfterInitialization(result, beanName);
			//如果途中某个processor的postProcessAfterInitialization方法返回null，那么不进行后续的回调
			//直接返回倒数第二个processor的postProcessAfterInitialization方法的返回值
			if (current == null) {
				return result;
			}
			//改变result指向当前的返回值
			result = current;
		}
		return result;
	}

	@Override
	public void destroyBean(Object existingBean) {
		new DisposableBeanAdapter(existingBean, getBeanPostProcessorCache().destructionAware).destroy();
	}


	//-------------------------------------------------------------------------
	// Delegate methods for resolving injection points
	//-------------------------------------------------------------------------

	@Override
	public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			return getBean(name, descriptor.getDependencyType());
		} finally {
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
		return resolveDependency(descriptor, requestingBeanName, null, null);
	}


	//---------------------------------------------------------------------
	// Implementation of relevant AbstractBeanFactory template methods
	//---------------------------------------------------------------------

	/**
	 * Central method of this class: creates a bean instance,
	 * populates the bean instance, applies post-processors, etc.
	 *
	 * @see #doCreateBean
	 * 参数 args 数组代表创建实例需要的参数，不就是给构造方法用的参数，或者是工厂 Bean 的参数
	 */
	@Override
	protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		if (logger.isTraceEnabled()) {
			logger.trace("Creating instance of bean '" + beanName + "'");
		}
		RootBeanDefinition mbdToUse = mbd;

		// Make sure bean class is actually resolved at this point, and
		// clone the bean definition in case of a dynamically resolved Class
		// which cannot be stored in the shared merged bean definition.
		//根据bean定义中的属性解析class，如果此前已经解析过了那么直接返回beanClass属性指定的Class对象，否则解析className字符串为Class对象
		Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
		if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
			// 如果resolvedClass存在，并且mdb的beanClass类型不是Class，并且mdb的beanClass不为空（则代表beanClass存的是Class的name）,
			// 则使用mdb深拷贝一个新的RootBeanDefinition副本，并且将解析的Class赋值给拷贝的RootBeanDefinition副本的beanClass属性，
			// 该拷贝副本取代mdb用于后续的操作
			mbdToUse = new RootBeanDefinition(mbd);
			mbdToUse.setBeanClass(resolvedClass);
		}

		// Prepare method overrides.
		// 准备方法覆写，这里又涉及到一个概念：MethodOverrides，它来自于 bean 定义中的 <lookup-method />
		// 和 <replaced-method />，如果读者感兴趣，回到 bean 解析的地方看看对这两个标签的解析。
		// 我在附录中也对这两个标签的相关知识点进行了介绍，读者可以移步去看看
		try {
			mbdToUse.prepareMethodOverrides();
		} catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(mbdToUse.getResourceDescription(),
					beanName, "Validation of method overrides failed", ex);
		}

		try {
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			// 实例化前的处理，给InstantiationAwareBeanPostProcessor一个机会返回代理对象来替代真正的bean实例，达到“短路”效果
			Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
			if (bean != null) {
				// 如果bean不为空，则会跳过Spring默认的实例化过程，直接使用返回的bean
				return bean;
			}
		} catch (Throwable ex) {
			throw new BeanCreationException(mbdToUse.getResourceDescription(), beanName,
					"BeanPostProcessor before instantiation of bean failed", ex);
		}

		try {
			//调用doCreateBean方法真正的创建bean实例
			Object beanInstance = doCreateBean(beanName, mbdToUse, args);
			if (logger.isTraceEnabled()) {
				logger.trace("Finished creating instance of bean '" + beanName + "'");
			}
			return beanInstance;
		} catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
			// A previously detected exception with proper bean creation context already,
			// or illegal singleton state to be communicated up to DefaultSingletonBeanRegistry.
			throw ex;
		} catch (Throwable ex) {
			throw new BeanCreationException(
					mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
		}
	}

	/**
	 * Actually create the specified bean. Pre-creation processing has already happened
	 * at this point, e.g. checking {@code postProcessBeforeInstantiation} callbacks.
	 * 实际上创建指定的 bean实例的方法。此时已完成预创建处理，比如postProcessBeforeInstantiation回调。
	 * 支持使用默认构造器、使用工厂方法和自动注入带参构造器实例化bean。
	 * 回调所有配置的initMethod方法
	 * <p>Differentiates between default bean instantiation, use of a
	 * factory method, and autowiring a constructor.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the merged bean definition for the bean
	 * @param args     explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
	 * @see #instantiateBean
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 */
	protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException {

		// Instantiate the bean.
		// 1.新建Bean包装类
		BeanWrapper instanceWrapper = null;
		if (mbd.isSingleton()) {
			// 2.如果是FactoryBean，则需要先移除未完成的FactoryBean实例的缓存
			instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
		}
		if (instanceWrapper == null) {
			// 3.根据beanName、mbd、args，使用对应的策略创建Bean实例，并返回包装类BeanWrapper
			instanceWrapper = createBeanInstance(beanName, mbd, args);
		}
		// 4.拿到创建好的Bean实例
		final Object bean = instanceWrapper.getWrappedInstance();
		// 5.拿到Bean实例的类型
		Class<?> beanType = instanceWrapper.getWrappedClass();
		if (beanType != NullBean.class) {
			mbd.resolvedTargetType = beanType;
		}

		// Allow post-processors to modify the merged bean definition.
		synchronized (mbd.postProcessingLock) {
			if (!mbd.postProcessed) {
				try {
					// 6.应用后置处理器MergedBeanDefinitionPostProcessor，允许修改MergedBeanDefinition，
					// Autowired注解正是通过此方法实现注入类型的预解析
					applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
				} catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Post-processing of merged bean definition failed", ex);
				}
				mbd.markAsPostProcessed();
			}
		}

		// Eagerly cache singletons to be able to resolve circular references
		// even when triggered by lifecycle interfaces like BeanFactoryAware.
		// 7.判断是否需要提早曝光实例：单例 && 允许循环依赖 && 当前bean正在创建中
		boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
				isSingletonCurrentlyInCreation(beanName));
		if (earlySingletonExposure) {
			if (logger.isTraceEnabled()) {
				logger.trace("Eagerly caching bean '" + beanName +
						"' to allow for resolving potential circular references");
			}

			// 8.提前曝光beanName的ObjectFactory，用于解决循环引用
			addSingletonFactory(beanName, () -> {
				// 8.1 应用后置处理器SmartInstantiationAwareBeanPostProcessor，允许返回指定bean的早期引用，若没有则直接返回bean
				return getEarlyBeanReference(beanName, mbd, bean);
			});
		}

		// Initialize the bean instance.  初始化bean实例。
		Object exposedObject = bean;
		try {
			// 9.对bean进行属性填充；其中，可能存在依赖于其他bean的属性，则会递归初始化依赖的bean实例
			populateBean(beanName, mbd, instanceWrapper);
			// 10.对bean进行初始化
			exposedObject = initializeBean(beanName, exposedObject, mbd);
		} catch (Throwable ex) {
			if (ex instanceof BeanCreationException bce && beanName.equals(bce.getBeanName())) {
				throw bce;
			} else {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
			}
		}

		if (earlySingletonExposure) {
			// 11.如果允许提前曝光实例，则进行循环依赖检查
			Object earlySingletonReference = getSingleton(beanName, false);
			// 11.1 earlySingletonReference只有在当前解析的bean存在循环依赖的情况下才会不为空
			if (earlySingletonReference != null) {
				if (exposedObject == bean) {
					// 11.2 如果exposedObject没有在initializeBean方法中被增强，则不影响之前的循环引用
					exposedObject = earlySingletonReference;
				} else if (!this.allowRawInjectionDespiteWrapping && hasDependentBean(beanName)) {
					// 11.3 如果exposedObject在initializeBean方法中被增强 && 不允许在循环引用的情况下使用注入原始bean实例
					// && 当前bean有被其他bean依赖

					// 11.4 拿到依赖当前bean的所有bean的beanName数组
					String[] dependentBeans = getDependentBeans(beanName);
					Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
					for (String dependentBean : dependentBeans) {
						// 11.5 尝试移除这些bean的实例，因为这些bean依赖的bean已经被增强了，他们依赖的bean相当于脏数据
						if (!removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
							// 11.6 移除失败的添加到 actualDependentBeans
							actualDependentBeans.add(dependentBean);
						}
					}
					if (!actualDependentBeans.isEmpty()) {
						// 11.7 如果存在移除失败的，则抛出异常，因为存在bean依赖了“脏数据”
						throw new BeanCurrentlyInCreationException(beanName,
								"Bean with name '" + beanName + "' has been injected into other beans [" +
										StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
										"] in its raw version as part of a circular reference, but has eventually been " +
										"wrapped. This means that said other beans do not use the final version of the " +
										"bean. This is often the result of over-eager type matching - consider using " +
										"'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
					}
				}
			}
		}

		// Register bean as disposable.
		try {
			// 12.注册用于销毁的bean，执行销毁操作的有三种：自定义destroy方法、DisposableBean接口、DestructionAwareBeanPostProcessor
			registerDisposableBeanIfNecessary(beanName, bean, mbd);
		} catch (BeanDefinitionValidationException ex) {
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
		}
		// 13.完成创建并返回
		return exposedObject;
	}

	/**
	 * <p>预测指定beanName,mbd所提供的信息的最终bean类型
	 * 	<ol>
	 * 	    <li>获取mbd的目标类型，赋值给【targetType】</li>
	 * 	    <li>如果mbd的目标类型不为null 且 mbd是由应用程序本身定义的 且 该工厂有 InstantiationAwareBeanPostProcessor
	 * 	    (一般情况下工厂都会有 InstantiationAwareBeanPostProcessor)：
	 * 	     <ol>
	 * 	       <li>根据typesToMatch构建要匹配的类型只有FactoryBean标记</li>
	 * 	       <li>遍历该工厂创建的bean的BeanPostProcessors列表,元素有bp：
	 * 	         <ol>
	 * 	           <li>将bp强转成SmartInstantiationAwareBeanPostProcessor对象【变量bp】</li>
	 * 	           <li>调用ibp的predictBeanType(targetType, beanName)方法获取预测的最终类型【变量 predicted】</li>
	 * 	           <li>如果predicated不为null 且 (typesToMatch构建要匹配的类型不只有 FactoryBean 或者 predicted属于FactoryBean,就返回predicated</li>
	 * 	         </ol>
	 * 	       </li>
	 * 	     </ol>
	 * 	    </li>
	 * 	</ol>
	 * </p>
	 *
	 * @param beanName     the name of the bean -- bean名
	 * @param mbd          the merged bean definition to determine the type for
	 *                     -- 合并的bean定义以确定其类型
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 *                     (also signals that the returned {@code Class} will never be exposed to application code)
	 *                     -- 内部类型培评时要匹配的类型（也表示返回的Class永远不会保留应用程序代码）
	 * @return
	 */
	@Override
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		//获取mbd的目标类型
		Class<?> targetType = determineTargetType(beanName, mbd, typesToMatch);
		// Apply SmartInstantiationAwareBeanPostProcessors to predict the
		// eventual type after a before-instantiation shortcut.
		// 应用SmartInstantiationAwareBeanPostProcessors来预测实例化快捷方式后的最终类型
		// 如果mbd的目标类型不为null 且 mbd是由应用程序本身定义的 且 该工厂有 InstantiationAwareBeanPostProcessor
		if (targetType != null && !mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			//根据typesToMatch构建要匹配的类型只有FactoryBean标记
			boolean matchingOnlyFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
			//遍历该工厂创建的bean的BeanPostProcessors列表
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				//从ibp中获取其最终bean类型
				Class<?> predicted = bp.predictBeanType(targetType, beanName);
				//如果predicated不为null 且 (typesToMatch构建要匹配的类型不只有FactoryBean 或者 predicted属于FactoryBean
				if (predicted != null &&
						(!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
					return predicted;
				}
			}
		}
		return targetType;
	}

	/**
	 * <p>确定给定的bean定义的目标类型：
	 * 	<ol>
	 * 	    <li>从mbd中获取目标类型【targetType】,获取成功就返回</li>
	 * 	    <li>如果bean的合并定义中有设置工厂方法名，就通过工厂方法区解析出targetType</li>
	 * 	    <li>否则调用resolveBeanClass(mbd, beanName, typesToMatch))解析出targetType</li>
	 * 	    <li>如果typeToMatch为空数组 或者 该工厂没有临时类加载器,缓存解析出来的targetType到mbd中，以免重新解析.</li>
	 * 	    <li>返回targetType</li>
	 * 	</ol>
	 * </p>
	 * Determine the target type for the given bean definition.
	 * <p>确定给定的bean定义的目标类型</p>
	 *
	 * @param beanName     the name of the bean (for error handling purposes) -- bean名(用于错误处理）
	 * @param mbd          the merged bean definition for the bean -- bean的合并bean定义
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 *                     (also signals that the returned {@code Class} will never be exposed to application code)
	 *                     -- 如果要进行内部类型匹配，则要进行匹配（也表明返回Class永远不会暴露给应用程序代码）
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * --	Bean的类型（如果可以确定的话），否则为 null
	 */
	@Nullable
	protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		//获取bean的合并定义的目标类型
		Class<?> targetType = mbd.getTargetType();
		//如果没有成功获取到目标类型
		if (targetType == null) {
			//如果bean的合并定义中有设置工厂方法名，就通过工厂方法区解析出targetType,否则
			//交给resolveBeanClass方法解析出targetType
			if (mbd.getFactoryMethodName() != null) {
				targetType = getTypeForFactoryMethod(beanName, mbd, typesToMatch);
			} else {
				targetType = resolveBeanClass(mbd, beanName, typesToMatch);
				if (mbd.hasBeanClass()) {
					targetType = getInstantiationStrategy().getActualBeanClass(mbd, beanName, this);
				}
			}
			//如果typeToMatch为空数组 或者 该工厂没有临时类加载器
			if (ObjectUtils.isEmpty(typesToMatch) || getTempClassLoader() == null) {
				//缓存解析出来的targetType到mbd中，以免重新解析
				mbd.resolvedTargetType = targetType;
			}
		}
		return targetType;
	}

	/**
	 * <p>从工厂方法确定给定bean定义的目标类型,仅在尚未为目标bean注册单例实例时调用:
	 * 	<ol>
	 * 	    <li>获取mbd的工厂方法返回类型【RootBeanDefinition#factoryMethodReturnType】,获取成功就返回出去</li>
	 * 	    <li>定一个通用返回类型【变量commonType】，用于存储 经过比较 AutowireUtils#resolveReturnTypeForFactoryMethod方法的返回结果
	 * 	    和Method#getReturnType方法的返回结果所得到共同父类。下面步骤都是为了获取commonType所实施的。</li>
	 * 	    <li>尝试获取bean的合并bean定义中的缓存用于自省的唯一工厂方法对象【RootBeanDefinition#factoryMethodToIntrospect】
	 * 	    【变量uniqueCandidate】,没成功获取到uniqueCandidate就通过下面步骤获取：
	 * 	    	<ol>
	 * 	    	  <li>定义一个mbd指定的工厂类【变量factoryClass】</li>
	 * 	    	  <li>定义一个表明uniqueCandidate是否是静态方法的标记，默认是true【变量isStatic】</li>
	 * 	    	  <li>获取mbd的FactoryBean名【变量factoryBeanName】
	 * 	    	  	<ol>
	 * 	    	  	   <li>如果获取成功，就意味着需要得到factoryBeanName所指的实例对象才能调用uniqueCandidate，
	 * 	    	  	   即uniqueCandidate不是静态方法:
	 * 	    	  	    <ol>
	 * 	    	  	      <li>如果factoryBeanName与beanName相等,会抛出BeanDefinitionStoreException,表明FactoryBean引用指向
	 * 	    	  	      相同的BeanDefinition</li>
	 * 	    	  	      <li>调用getType(factoryBeanName)获取其对应的类型【变量factoryClass】</li>
	 * 	    	  	      <li>isStatic设置为false，表示uniqueCandidate不是静态方法</li>
	 * 	    	  	    </ol>
	 * 	    	  	   </li>
	 * 	    	  	   <li>否则，调用resolveBeanClass(mbd, beanName, typesToMatch)来得到factoryClass</li>
	 * 	    	  	</ol>
	 * 	    	  </li>
	 * 	    	  <li>如果经过上面步骤，factoryClass还是没有成功获取就返回null,表示找到不明确的返回类型</li>
	 * 	    	  <li>如果mbd有配置构造函数参数值，就获取该构造函数参数值的数量，否则为0【变量 minNrOfArgs】</li>
	 * 	    	  <li>从该工厂的缓存候选工厂方法集合【factoryMethodCandidateCache】中获取候选方法，如果没有就调用
	 * 	    	  ReflectionUtils.getUniqueDeclaredMethods(factoryClass, ReflectionUtils.USER_DECLARED_METHODS))来
	 * 	    	  获取并添加到factoryMethodCandidateCache中【变量 candidates】
	 * 	    	  </li>
	 * 	    	  <li>遍历candidates，元素为candidate.当candidate是否静态的判断结果与isStatic一致 且 candidate有资格作为工厂方法
	 * 	    	  且candidate的方法参数数量>=minNrOfArgs时:
	 * 	    	     <ol>
	 * 	    	       <li>如果candidate的参数数量>0:
	 * 	    	         <ol>
	 * 	    	           <li>获取candidate的参数类型数组【变量 paramTypes】</li>
	 * 	    	           <li>使用该工厂的参数名发现器【parameterNameDiscoverer】获取candidate的参数名 【变量 paramNames】</li>
	 * 	    	           <li>获取mbd的构造函数参数值 【变量 cav】</li>
	 * 	    	           <li>定义一个存储构造函数参数值ValueHolder对象的HashSet【变量 usedValueHolders】</li>
	 * 	    	           <li>定义一个用于存储参数值的数组【变量 args】</li>
	 * 	    	           <li>遍历args,索引为i：
	 * 	    	            <ol>
	 * 	    	              <li>获取第i个构造函数参数值ValueHolder对象【变量 valueHolder】,尽可能的提供位置，参数类型,参数名
	 * 	    	              以最精准的方式获取获取第i个构造函数参数值ValueHolder对象，传入usedValueHolder来提示cav#getArgumentValue方法
	 * 	    	              不应再次返回该usedValueHolder所出现的ValueHolder对象(如果有 多个类型的通用参数值，则允许返回下一个通用参数匹配项)</li>
	 * 	    	              <li>如果valueHolder获取失败,使用不匹配类型，不匹配参数名的方式获取除userValueHolders以外的
	 * 	    	              下一个参数值valueHolder对象</li>
	 * 	    	              <li>如果valueHolder获取成功,从valueHolder中获取值保存到args[i],然后将valueHolder添加到usedValueHolders缓存中，
	 * 	    	              表示该valueHolder已经使用过</li>
	 * 	    	            </ol>
	 * 	    	           </li>
	 * 	    	           <li>调用AutowireUtils.resolveReturnTypeForFactoryMethod(candidate, args, getBeanClassLoader())获取
	 * 	    	           candidate的最终返回类型</li>
	 * 	    	           <li>如果commnType为null 且 returnType等于candidate直接获取的返回类型，uniqueCandidate就是candiate，否则为null</li>
	 * 	    	           <li>如果commonType为null就返回null，表示找到不明确的返回类型</li>
	 * 	    	           <li>捕捉获取commonType的所有异常,不再抛出任何异常，只打印出调试日志无法为工厂方法解析通用返回类型</li>
	 * 	    	         </ol>
	 * 	    	       </li>
	 * 	    	       <li>如果candidate无需参数:
	 * 	    	       	<ol>
	 * 	    	       	  <li>如果还没有找到commonType，candidate就为uniqueCandidate</li>
	 * 	    	       	  <li>获取candidate返回类型与commonType的共同父类，将该父类重新赋值给commonType</li>
	 * 	    	       	  <li>如果commonType为null就返回null，表示找到不明确的返回类型</li>
	 * 	    	       	</ol>
	 * 	    	       </li>
	 * 	    	     </ol>
	 * 	    	    </li>
	 * 	    	    <li>缓存uniqueCandidate到mbd的factoryMethodToInstropect</li>
	 * 	    	    <li>如果commonType为null就返回null，表示找到不明确的返回类型。加上这个判断能保证下面的步骤commonType肯定有值</li>
	 * 	    	  </ol>
	 * 	    </li>
	 *      <li>如果获取到了uniqueCandidate就获取uniqueCandidate的返回类型，否则就用commonType作为返回类型【变量cachedReturnType】</li>
	 *      <li>缓存cachedReturnType到mdb的factoryMethodReturnType</li>
	 *      <li>返回cachedReturnType封装的Class对象</li>
	 * 	</ol>
	 * </p>
	 * Determine the target type for the given bean definition which is based on
	 * a factory method. Only called if there is no singleton instance registered
	 * for the target bean already.
	 * <p>工厂方法确定给定bean定义的目标类型。仅在尚未为目标bean注册单例实例时调用</p>
	 * <p>This implementation determines the type matching {@link #createBean}'s
	 * different creation strategies. As far as possible, we'll perform static
	 * type checking to avoid creation of the target bean.
	 * <p>此实现确定与createBean的不同创建策略匹配的类型。尽可能地，我们将执行静态类型
	 * 检查以避免创建目标bean</p>
	 *
	 * @param beanName     the name of the bean (for error handling purposes)
	 *                     -- bean名（用于错误处理）
	 * @param mbd          the merged bean definition for the bean
	 *                     -- bean的合并bean定义
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 *                     (also signals that the returned {@code Class} will never be exposed to application code)
	 *                     -- 内部类型匹配时要匹配的类型（也表示返回的Class永远不会暴露给应用程序代码）
	 * @return the type for the bean if determinable, or {@code null} otherwise
	 * -- Bean类型（如果可以确定的话），否则为 null
	 * @see #createBean
	 */
	@Nullable
	protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		//尝试获取bean的合并bean定义中的缓存工厂方法返回类型
		ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
		//如果成功获取到了bean的合并bean定义中的缓存工厂方法返回类型
		if (cachedReturnType != null) {
			//ResolvableType.resolve:将ResolvableType对象解析为Class,如果无法解析，则返回null
			return cachedReturnType.resolve();
		}

		//通用的返回类型，经过比较 AutowireUtils#resolveReturnTypeForFactoryMethod方法的返回结果
		// 和Method#getReturnType方法的返回结果所得到共同父类。
		Class<?> commonType = null;
		//尝试获取bean的合并bean定义中的缓存用于自省的唯一工厂方法对象
		Method uniqueCandidate = mbd.factoryMethodToIntrospect;
		//如果成功获取到了bean的合并bean定义中的缓存用于自省的唯一工厂方法对象
		if (uniqueCandidate == null) {
			Class<?> factoryClass;
			boolean isStatic = true;
			//获取bean的合并bean定义的工厂bean名
			String factoryBeanName = mbd.getFactoryBeanName();
			//如果成功获取到bean的合并bean定义的工厂bean名
			if (factoryBeanName != null) {
				//如果工厂bean名 与 生成该bean的bean名相等
				if (factoryBeanName.equals(beanName)) {
					//抛出 当BeanFactory遇到无效的bean定义时引发的异常 ：
					//  工厂bean引用指向相同的bean定义
					throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
							"factory-bean reference points back to the same bean definition");
				}
				// Check declared factory method return type on factory class.
				// 检查工厂类上声明的工厂方法返回类型,获取factoryBeanName对应的工厂类
				factoryClass = getType(factoryBeanName);
				isStatic = false;
			} else {
				// Check declared factory method return type on bean class.
				// 检查bean类上声明的工厂方法返回类型
				//为mbd解析bean类，将bean类名解析为Class引用（如果需要）,并将解析后的Class存储在
				// mbd中以备将来使用。
				factoryClass = resolveBeanClass(mbd, beanName, typesToMatch);
			}
			//如果mbd指定的工厂类获取失败
			if (factoryClass == null) {
				//返回null
				return null;
			}
			//如果factoryClass是CGLIB生成的子类，则返回factoryClass的父类，否则直接返回factoryClass
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// If all factory methods have the same return type, return that type.
			// Can't clearly figure out exact method due to type converting / autowiring!
			// 如果所有工厂方法都具有相同的返回类型，则返回该类型。
			// 由于类型转换/自动装配，无法明确找出确切的方法。
			// 如果mbd有配置构造函数参数值，就获取该构造函数参数值的数量，否则为0
			int minNrOfArgs =
					(mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
			//在子类和所有超类上获取一组唯一的已声明方法，即被重写非协变返回类型的方法
			// 首先包含子类方法和然后遍历父类层次结构任何方法，将过滤出所有与已包含的方法匹配的签名方法。
			Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
					clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

			//遍历候选方法
			for (Method candidate : candidates) {
				//如果candidate是否静态的判断结果与isStatic一致 且 candidate有资格作为工厂方法 且 candidate的方法参数数量>=minNrOfArgs
				if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
						candidate.getParameterCount() >= minNrOfArgs) {
					// Declared type variables to inspect?
					// 声明要检查的类型变量?
					// 如果candidate的参数数量>0
					if (candidate.getTypeParameters().length > 0) {
						try {
							// Fully resolve parameter names and argument values.
							// 完全解析参数名称和参数值
							// 获取candidate的参数类型数组
							Class<?>[] paramTypes = candidate.getParameterTypes();
							//参数名数组
							String[] paramNames = null;
							//获取参数名发现器
							ParameterNameDiscoverer pnd = getParameterNameDiscoverer();
							//如果pnd不为null
							if (pnd != null) {
								//使用pnd获取candidate的参数名
								paramNames = pnd.getParameterNames(candidate);
							}
							//获取mbd的构造函数参数值
							ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
							// HashSet:HashSet简单的理解就是HashSet对象中不能存储相同的数据，存储数据时是无序的。
							// 但是HashSet存储元素的顺序并不是按照存入时的顺序（和List显然不同） 是按照哈希值来存的所以取数据也是按照哈希值取得。
							// 定义一个存储构造函数参数值ValueHolder对象的HashSet
							Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
							// 定义一个用于存储参数值的数组
							Object[] args = new Object[paramTypes.length];
							//遍历参数值
							for (int i = 0; i < args.length; i++) {
								//获取第i个构造函数参数值ValueHolder对象
								//尽可能的提供位置，参数类型,参数名以最精准的方式获取获取第i个构造函数参数值ValueHolder对象，传入
								// usedValueHolder来提示cav#getArgumentValue方法不应再次返回该usedValueHolder所出现的ValueHolder对象
								// (如果有 多个类型的通用参数值，则允许返回下一个通用参数匹配项)
								ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
										i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
								//如果valueHolder获取失败
								if (valueHolder == null) {
									//使用不匹配类型，不匹配参数名的方式获取除userValueHolders以外的下一个参数值valueHolder对象
									valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
								}
								//如果valueHolder获取成功
								if (valueHolder != null) {
									//从valueHolder中获取值保存到第i个args元素中
									args[i] = valueHolder.getValue();
									//将valueHolder添加到usedValueHolders缓存中，表示该valueHolder已经使用过
									usedValueHolders.add(valueHolder);
								}
							}
							//获取candidate的最终返回类型，该方法支持泛型情况下的目标类型获取
							Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
									candidate, args, getBeanClassLoader());
							//如果commonType为null 且 returnType等于candidate直接获取的返回类型，唯一候选方法就是candiate，否则为null
							uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
									candidate : null);
							//获取returnType与commonType的共同父类，将该父类重新赋值给commonType
							commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
							//如果commonType为null
							if (commonType == null) {
								// Ambiguous return types found: return null to indicate "not determinable".
								// 找到不明确的返回类型：返回null表示'不可确定'
								return null;
							}
						} //捕捉获取commonType的所有异常
						catch (Throwable ex) {
							if (logger.isDebugEnabled()) {
								//无法为工厂方法解析通用返回类型
								logger.debug("Failed to resolve generic return type for factory method: " + ex);
							}
						}
					} else { //如果candidate无需参数
						//如果还没有找到commonType，candidate就为唯一的候选方法
						uniqueCandidate = (commonType == null ? candidate : null);
						//获取candidate返回类型与commonType的共同父类，将该父类重新赋值给commonType
						commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
						//如果commonType为null
						if (commonType == null) {
							// Ambiguous return types found: return null to indicate "not determinable".
							// 找到不明确的返回类型：返回null表示'不可确定'
							return null;
						}
					}
				}
			}
			//缓存uniqueCandidate到mbd的factoryMethodToIntrospect
			mbd.factoryMethodToIntrospect = uniqueCandidate;
			//如果commonType为null，加上这个判断能保证下面的步骤commonType肯定有值
			if (commonType == null) {
				// 找到不明确的返回类型：返回null表示'不可确定'
				return null;
			}
		}

		// Common return type found: all factory methods return same type. For a non-parameterized
		// unique candidate, cache the full type declaration context of the target factory method.
		// 找到常见的返回类型：所有工厂方法都返回相同的类型。对象非参数化的唯一候选者，缓存目标工厂方法的 完整类型声明上下文
		//如果获取到了uniqueCandidate就获取uniqueCandidate的返回类型，否则就用commonType作为返回类型
		cachedReturnType = (uniqueCandidate != null ?
				ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
		//缓存cachedReturnType到mdb的factoryMethodReturnType
		mbd.factoryMethodReturnType = cachedReturnType;
		//返回cachedReturnType封装的Class对象
		return cachedReturnType.resolve();
	}

	/**
	 * This implementation attempts to query the FactoryBean's generic parameter metadata
	 * if present to determine the object type. If not present, i.e. the FactoryBean is
	 * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
	 * on a plain instance of the FactoryBean, without bean properties applied yet.
	 * If this doesn't return a type yet, and {@code allowInit} is {@code true} a
	 * full creation of the FactoryBean is used as fallback (through delegation to the
	 * superclass's implementation).
	 * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
	 * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
	 * it will be fully created to check the type of its exposed object.
	 */
	@Override
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		// Check if the bean definition itself has defined the type with an attribute
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		ResolvableType beanType =
				(mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

		// For instance supplied beans try the target type and bean class
		if (mbd.getInstanceSupplier() != null) {
			result = getFactoryBeanGeneric(mbd.targetType);
			if (result.resolve() != null) {
				return result;
			}
			result = getFactoryBeanGeneric(beanType);
			if (result.resolve() != null) {
				return result;
			}
		}

		// Consider factory methods
		String factoryBeanName = mbd.getFactoryBeanName();
		String factoryMethodName = mbd.getFactoryMethodName();

		// Scan the factory bean methods
		if (factoryBeanName != null) {
			if (factoryMethodName != null) {
				// Try to obtain the FactoryBean's object type from its factory method
				// declaration without instantiating the containing bean at all.
				BeanDefinition factoryBeanDefinition = getBeanDefinition(factoryBeanName);
				Class<?> factoryBeanClass;
				if (factoryBeanDefinition instanceof AbstractBeanDefinition abstractBeanDefinition &&
						abstractBeanDefinition.hasBeanClass()) {
					factoryBeanClass = abstractBeanDefinition.getBeanClass();
				} else {
					RootBeanDefinition fbmbd = getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
					factoryBeanClass = determineTargetType(factoryBeanName, fbmbd);
				}
				if (factoryBeanClass != null) {
					result = getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
					if (result.resolve() != null) {
						return result;
					}
				}
			}
			// If not resolvable above and the referenced factory bean doesn't exist yet,
			// exit here - we don't want to force the creation of another bean just to
			// obtain a FactoryBean's object type...
			if (!isBeanEligibleForMetadataCaching(factoryBeanName)) {
				return ResolvableType.NONE;
			}
		}

		// If we're allowed, we can create the factory bean and call getObjectType() early
		if (allowInit) {
			FactoryBean<?> factoryBean = (mbd.isSingleton() ?
					getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
					getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
			if (factoryBean != null) {
				// Try to obtain the FactoryBean's object type from this early stage of the instance.
				Class<?> type = getTypeForFactoryBean(factoryBean);
				if (type != null) {
					return ResolvableType.forClass(type);
				}
				// No type found for shortcut FactoryBean instance:
				// fall back to full creation of the FactoryBean instance.
				return super.getTypeForFactoryBean(beanName, mbd, true);
			}
		}

		if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
			// No early bean instantiation possible: determine FactoryBean's type from
			// static factory method signature or from class inheritance hierarchy...
			return getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
		}
		result = getFactoryBeanGeneric(beanType);
		if (result.resolve() != null) {
			return result;
		}
		return ResolvableType.NONE;
	}

	private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
		if (type == null) {
			return ResolvableType.NONE;
		}
		return type.as(FactoryBean.class).getGeneric();
	}

	/**
	 * Introspect the factory method signatures on the given bean class,
	 * trying to find a common {@code FactoryBean} object type declared there.
	 *
	 * @param beanClass         the bean class to find the factory method on
	 * @param factoryMethodName the name of the factory method
	 * @return the common {@code FactoryBean} object type, or {@code null} if none
	 */
	private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
		// CGLIB subclass methods hide generic parameters; look at the original user class.
		Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
		FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
		ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
		return finder.getResult();
	}

	/**
	 * Obtain a reference for early access to the specified bean,
	 * typically for the purpose of resolving a circular reference.
	 * 应用SmartInstantiationAwareBeanPostProcessor后处理器的getEarlyBeanReference方法
	 * 该方法可以改变要返回的提前暴露的单例bean引用对象
	 * 获取用于早期访问的bean 的引用，通常用于解析循环引用，只有单例bean会调用该方法
	 *
	 * @param beanName the name of the bean (for error handling purposes)
	 * @param mbd      the merged bean definition for the bean
	 * @param bean     the raw bean instance
	 * @return the object to expose as bean reference
	 */
	protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
		Object exposedObject = bean;
		// 1.如果bean不为空 && mbd不是合成 && 存在InstantiationAwareBeanPostProcessors
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (BeanPostProcessor bp : getBeanPostProcessors()) {
				// 2.应用所有SmartInstantiationAwareBeanPostProcessor，调用getEarlyBeanReference方法
				if (bp instanceof SmartInstantiationAwareBeanPostProcessor ibp) {
					// 3.允许SmartInstantiationAwareBeanPostProcessor返回指定bean的早期引用
					exposedObject = ibp.getEarlyBeanReference(exposedObject, beanName);
				}
			}
		}
		// 4.返回要作为bean引用公开的对象，如果没有SmartInstantiationAwareBeanPostProcessor修改，则返回的是入参的bean对象本身
		return exposedObject;
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * Obtain a "shortcut" singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		synchronized (getSingletonMutex()) {
			BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
			if (bw != null) {
				return (FactoryBean<?>) bw.getWrappedInstance();
			}
			Object beanInstance = getSingleton(beanName, false);
			if (beanInstance instanceof FactoryBean<?> factoryBean) {
				return factoryBean;
			}
			if (isSingletonCurrentlyInCreation(beanName) ||
					(mbd.getFactoryBeanName() != null && isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
				return null;
			}

			Object instance;
			try {
				// Mark this bean as currently in creation, even if just partially.
				beforeSingletonCreation(beanName);
				// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
				instance = resolveBeforeInstantiation(beanName, mbd);
				if (instance == null) {
					bw = createBeanInstance(beanName, mbd, null);
					instance = bw.getWrappedInstance();
				}
			} catch (UnsatisfiedDependencyException ex) {
				// Don't swallow, probably misconfiguration...
				throw ex;
			} catch (BeanCreationException ex) {
				// Don't swallow a linkage error since it contains a full stacktrace on
				// first occurrence... and just a plain NoClassDefFoundError afterwards.
				if (ex.contains(LinkageError.class)) {
					throw ex;
				}
				// Instantiation failure, maybe too early...
				if (logger.isDebugEnabled()) {
					logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
				}
				onSuppressedException(ex);
				return null;
			} finally {
				// Finished partial creation of this bean.
				afterSingletonCreation(beanName);
			}

			FactoryBean<?> fb = getFactoryBean(beanName, instance);
			if (bw != null) {
				this.factoryBeanInstanceCache.put(beanName, bw);
			}
			return fb;
		}
	}

	/**
	 * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
	 * {@code getObjectType()} call, without full initialization of the FactoryBean.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @return the FactoryBean instance, or {@code null} to indicate
	 * that we couldn't obtain a shortcut FactoryBean instance
	 */
	@Nullable
	private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
		if (isPrototypeCurrentlyInCreation(beanName)) {
			return null;
		}

		Object instance;
		try {
			// Mark this bean as currently in creation, even if just partially.
			beforePrototypeCreation(beanName);
			// Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
			instance = resolveBeforeInstantiation(beanName, mbd);
			if (instance == null) {
				BeanWrapper bw = createBeanInstance(beanName, mbd, null);
				instance = bw.getWrappedInstance();
			}
		} catch (UnsatisfiedDependencyException ex) {
			// Don't swallow, probably misconfiguration...
			throw ex;
		} catch (BeanCreationException ex) {
			// Instantiation failure, maybe too early...
			if (logger.isDebugEnabled()) {
				logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
			}
			onSuppressedException(ex);
			return null;
		} finally {
			// Finished partial creation of this bean.
			afterPrototypeCreation(beanName);
		}

		return getFactoryBean(beanName, instance);
	}

	/**
	 * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
	 * invoking their {@code postProcessMergedBeanDefinition} methods.
	 * <p>将 MergedBeanDefinitionPostProcessors 应用到指定的 BeanDefinition ,调用它们的
	 * postProcessMergedBeanDefinition 方法
	 *
	 * @param mbd      the merged bean definition for the bean  bean的合并后beanDefinition
	 * @param beanType the actual type of the managed bean instance 托管bean实例的实际类型
	 * @param beanName the name of the bean。 bean名称
	 * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
	 */
	protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			//回调postProcessMergedBeanDefinition方法，一般MergedBeanDefinitionPostProcessor会做一些 内省BeanDefinition,
			//以便对bean 的实际实例进行后处理之前准备一些缓存的元数据，它也可以修改beanDefinition,但只允许用于实际用于并发 修改的BeanDefinition属性。
			//本质上，这只适用于在RootBeanDefinition本身定义的操作，而不适用于其基类
			processor.postProcessMergedBeanDefinition(mbd, beanType, beanName);
		}
	}

	/**
	 * <p>应用InstantiationAwareBeanPostProcessor后处理器实例化benName的实例对象：
	 *  <ol>
	 *    <li>定义一个【变量bean】默认为null，表示没有InstantiationAwareBeanPostProcessor后处理器
	 *    可实例化beanName的实例对象</li>
	 *    <li>如果mdb还没有启动实例化前的后处理器【RootBeanDefinition#beforeInstantiationResolved】：
	 *     <ol>
	 *       <li>如果mbd不是合成的 且 该工厂拥有InstiationAwareBeanPostProcessor:
	 *        <ol>
	 *          <li>确定mbd的目标类型，【变量targetType】</li>
	 *          <li>如果成功获取到了targetType:
	 *           <ol>
	 *             <li>在实例化之前应用 InstantiationAwareBeanPostProcessor 后处理器,并尝试通过BeanPostProcess创建
	 *             beanName&beanClass的单例对象 【变量 bean】</li>
	 *             <li>如果成功获取bean,就应用所有BeanPostProcess对bean进行后处理包装。</li>
	 *           </ol>
	 *          </li>
	 *        </ol>
	 *       </li>
	 *       <li>当bean实例化成功后，对mbd加上已启动实例化前的后处理器的标记【RootBeanDefinition#beforeInstantiationResolved】</li>
	 *     </ol>
	 *    </li>
	 *    <li>返回bean</li>
	 *  </ol>
	 * </p>
	 * Apply before-instantiation post-processors, resolving whether there is a
	 * before-instantiation shortcut for the specified bean.
	 * <p>应用实例化之前的后处理器，以解决指定的bean是否存在实例化快捷方式</p>
	 *
	 * @param beanName the name of the bean --bean名
	 * @param mbd      the bean definition for the bean -- bean的合并后BeanDefinition
	 * @return the shortcut-determined bean instance, or {@code null} if none
	 * -- 快捷方式确定的bean实例；如果没有，则未null
	 */
	@Nullable
	protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
		Object bean = null;
		//判断初始化之前有没有处理，有的话直接返回null
		if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
			// Make sure bean class is actually resolved at this point.
			// 1.mbd不是合成的，并且BeanFactory中存在InstantiationAwareBeanPostProcessor
			if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
				// 2.解析beanName对应的Bean实例的类型
				Class<?> targetType = determineTargetType(beanName, mbd);
				if (targetType != null) {
					// 3.实例化前的后置处理器应用（处理InstantiationAwareBeanPostProcessor）
					bean = applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
					if (bean != null) {
						// 4.如果返回的bean不为空，会跳过Spring默认的实例化过程，
						// 所以只能在这里调用BeanPostProcessor实现类的postProcessAfterInitialization方法
						bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);
					}
				}
			}
			// 5.如果bean不为空，则将beforeInstantiationResolved赋值为true，代表在实例化之前已经解析
			mbd.beforeInstantiationResolved = (bean != null);
		}
		return bean;
	}

	/**
	 * <p>
	 * 在实例化之前应用Bean后处理器,并尝试通过Bean后处理器创建beanName&beanClass的单例对象
	 *     <ol>
	 *       <li>遍历该工厂创建的bean的BeanPostProcessors列表,元素为bp:
	 *        <ol>
	 *          <li>如果 bp 是 InstantiationAwareBeanPostProcessor 的实例:
	 *           <ol>
	 *             <li>将 bp 强转为InstantiationAwareBeanPostProcessor对象</li>
	 *             <li>调用postProcessBeforeInstantiation方法得到beanName指定的实例对象</li>
	 *             <li>如果该实例对象获取成功,直接返回该实例对象</li>
	 *           </ol>
	 *          </li>
	 *        </ol>
	 *       </li>
	 *       <li>返回null，表示该beanClass/beanName未能通过InstantiationAwareBeanPostProcessor实例化出指定实例对象</li>
	 *     </ol>
	 * </p>
	 * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
	 * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
	 * <p>将 InstantiationAwareBeanPostProcessors 应用于指定的beanDefinition(按类和名称),并
	 * 调用他们的 postProcessBeforeInstantiation 方法</p>
	 * <p>Any returned object will be used as the bean instead of actually instantiating
	 * the target bean. A {@code null} return value from the post-processor will
	 * result in the target bean being instantiated.
	 * <p>任何返回的对象都将用做bean，而不是实际实例化目标bean。后置处理器返回的空值将导致目标bean
	 * 被实例化。</p>
	 *
	 * @param beanClass the class of the bean to be instantiated -- 要实例化的bean类型
	 * @param beanName  the name of the bean -- bean名
	 * @return the bean object to use instead of a default instance of the target bean, or {@code null}
	 * -- 要使用bean的bean对象，而不是目标的默认实例，或 null
	 * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
	 */
	@Nullable
	protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
		// 1.遍历当前BeanFactory中的BeanPostProcessor
		for (BeanPostProcessor bp : getBeanPostProcessors()) {
			// 2.应用InstantiationAwareBeanPostProcessor后置处理器，允许postProcessBeforeInstantiation方法返回bean对象的代理
			if (bp instanceof InstantiationAwareBeanPostProcessor ibp) {
				// 3.执行postProcessBeforeInstantiation方法，在Bean实例化前操作，
				// 该方法可以返回一个构造完成的Bean实例，从而不会继续执行创建Bean实例的“正规的流程”，达到“短路”的效果。
				Object result = ibp.postProcessBeforeInstantiation(beanClass, beanName);
				if (result != null) {
					// 4.如果result不为空，也就是有后置处理器返回了bean实例对象，则会跳过Spring默认的实例化过程
					return result;
				}
			}
		}
		return null;
	}

	/**
	 * 使用适当的实例化策略为指定的Bean创建一个新实例：工厂方法，构造函数自动装配或简单实例化。
	 * <ol>
	 *  <li>使用工厂方法（多个工厂方法时，会找到最匹配的那个构造函数）,优先使用 args的参数值来实例化对象，
	 *  没有就使用mdb所定义的参数值</li>
	 *  <li>使用构造函数：
	 *   <ol>
	 *    <li>从SmartInstantiationAwareBeanPostProcessor中获取给定bean的候选构造函数 ||
	 *    mdb的解析自动注入模式为 按构造器自动装配 || mbd有构造函数参数 || args不为null,会以自动注入
	 *    方式调用最匹配的构造函数来实例化参数对象并返回出去</li>
	 *    <li>从mbd中获取首选的构造函数，以自动注入方式调用最匹配的构造函数来实例化参数对象并返回出去</li>
	 *    <li>无须特殊处理，只需使用无参数的构造函数</li>
	 *   </ol>
	 *  </li>
	 * </ol>
	 * Create a new instance for the specified bean, using an appropriate instantiation strategy:
	 * factory method, constructor autowiring, or simple instantiation.
	 * <p>使用适当的实例化策略为指定的Bean创建一个新实例：工厂方法，构造函数自动装配或简单实例化。</p>
	 *
	 * @param beanName the name of the bean -- bean名
	 * @param mbd      the bean definition for the bean -- bean的BeanDefinition
	 * @param args     explicit arguments to use for constructor or factory method invocation
	 *                 -- 用于构造函数或工厂方法调用的显示参数
	 * @return a BeanWrapper for the new instance -- 新实例的BeanWrapper
	 * @see #obtainFromSupplier
	 * @see #instantiateUsingFactoryMethod
	 * @see #autowireConstructor
	 * @see #instantiateBean
	 */
	protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
		// Make sure bean class is actually resolved at this point.
		// 得到bean的class  使用类加载器根据设置的 class 属性或者根据 className 来解析 Class
		Class<?> beanClass = resolveBeanClass(mbd, beanName);

		// 如果bean class不是public 且不允许访问非public方法和属性则抛出异常
		if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
		}

		// 通过factoryMethod实例化这个bean
		// factorMethod这个名称在xml中还是比较常见的, 即通过工厂方法来创建bean对象
		// 如果一个bean对象是由@Bean注解创建的, 也会走instantiateUsingFactoryMethod方法来创
		Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
		if (instanceSupplier != null) {
			return obtainFromSupplier(instanceSupplier, beanName);
		}

		if (mbd.getFactoryMethodName() != null) {
			// 采用工厂方法实例化
			return instantiateUsingFactoryMethod(beanName, mbd, args);
		}

		// Shortcut when re-creating the same bean...
		// 防止重复创建标识
		boolean resolved = false;
		// 是否需要自动装配
		boolean autowireNecessary = false;
		// 当作用域为原型、多次调用getBean()时，不传入参数，从缓存中获取这段逻辑才会被执行
		// 如果是单例，第二次调用 getBean()，直接从单例池获取对象了，根本就不会走到这里
		if (args == null) {
			synchronized (mbd.constructorArgumentLock) {
				// resolvedConstructorOrFactoryMethod 缓存了已解析的构造函数或工厂方法
				if (mbd.resolvedConstructorOrFactoryMethod != null) {
					// resolved为true，表示当前bean的构造方法已经确定了，也代表该Bean之前被解析过
					resolved = true;
					// constructorArgumentsResolved：将构造函数参数标记为已解析，true就是标记为了已解析
					// 默认为 false。
					// 如果autowireNecessary为true说明是采用有参构造函数注入
					autowireNecessary = mbd.constructorArgumentsResolved;
				}
			}
		}
		if (resolved) {
			// resolved为true，表示当前bean的构造方法已经确定了，也代表该Bean之前被解析过
			// autowireNecessary表示采用有参构造函数注入
			if (autowireNecessary) {
				// 构造函数依赖注入
				return autowireConstructor(beanName, mbd, null, null);
			} else {
				// 无参构造函数
				return instantiateBean(beanName, mbd);
			}
		}

		// Candidate constructors for autowiring?
		/*
		 * 利用SmartInstantiationAwareBeanPostProcessor后处理器回调，自动匹配、推测需要使用的候选构造器数组ctors
		 * 这里的是解析注解，比如@Autowired注解，因此需要开启注解支持
		 */
		Constructor<?>[] ctors = determineConstructorsFromBeanPostProcessors(beanClass, beanName);
		if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR ||
				mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
			// 构造函数依赖注入
			return autowireConstructor(beanName, mbd, ctors, args);
		}

		// Preferred constructors for default construction?
		// 通过BeanPostProcessor找出了构造方法
		// 或者BeanDefinition的autowire属性为AUTOWIRE_CONSTRUCTOR xml中使用了 autowire="constructor"
		// 或者BeanDefinition中指定了构造方法参数值 使用了 <constructor-arg>标签
		// 或者在getBean()时指定了args
		ctors = mbd.getPreferredConstructors();
		if (ctors != null) {
			return autowireConstructor(beanName, mbd, ctors, null);
		}

		// No special handling: simply use no-arg constructor.
		// 调用无参构造函数
		return instantiateBean(beanName, mbd);
	}

	/**
	 * <p>从给定的供应商那里获取一个bean实例,并对其包装成BeanWrapper对象：
	 *  <ol>
	 *   <li>声明一个实例对象【变量 instance】</li>
	 *   <li>从线程本地当前创建的bean名称【currentlyCreatedBean】中获取原先创建bean的名字【变量 outerBean】</li>
	 *   <li>保存beanName到currentlyCreatedBean中</li>
	 *   <li>从配置的Supplier中获取一个bean实例,赋值instance</li>
	 *   <li>【finally】如果原先bean存在,将保存到currentlyCreatedBean中;否则就将beanName移除</li>
	 *   <li>如果没有成功获取到instance,instance就引用NullBean</li>
	 *   <li>对instance包装成BeanWrapper对象【变量 bw】</li>
	 *   <li>初始化bw 【{@link #initBeanWrapper(BeanWrapper)}】</li>
	 *   <li>返回初始化后的bw</li>
	 *  </ol>
	 * </p>
	 * Obtain a bean instance from the given supplier.
	 * <p>从给定的供应商那里获取一个bean实例</p>
	 * @param beanName the corresponding bean name -- 对应的bean名
	 * @return a BeanWrapper for the new instance -- 新实例的BeanWrapper
	 * @since 5.0
	 * @see #getObjectForBeanInstance
	 */
	protected BeanWrapper obtainFromSupplier(Supplier<?> supplier, String beanName) {
		Object instance = obtainInstanceFromSupplier(supplier, beanName);
		//如果生产者返回null，那么返回NullBean包装bean
		if (instance == null) {
			instance = new NullBean();
		}
		// BeanWrapperImpl类是对BeanWrapper接口的默认实现，它包装了一个bean对象，
		// 缓存了bean的内省结果， 并可以访问bean的属性、设置bean的属性值。BeanWrapperImpl
		// 类提供了许多默认属性编辑器， 支持多种不同类型的类型转换，可以将数组、集合类型的属
		// 性转换成指定特殊类型的数组或集合。 用户也可以注册自定义的属性编辑器在BeanWrapperImpl中。
		//对instance进行包装
		BeanWrapper bw = new BeanWrapperImpl(instance);
		//初始化BeanWrapper
		initBeanWrapper(bw);
		return bw;
	}

	@Nullable
	private Object obtainInstanceFromSupplier(Supplier<?> supplier, String beanName) {
		//获取当前线程正在创建的 bean 的名称支持obtainFromSupplier方法加入的属性
		String outerBean = this.currentlyCreatedBean.get();
		// 设置 beanName到 currentlyCreatedBean 中
		this.currentlyCreatedBean.set(beanName);
		try {
			if (supplier instanceof InstanceSupplier<?> instanceSupplier) {
				return instanceSupplier.get(RegisteredBean.of((ConfigurableListableBeanFactory) this, beanName));
			}
			if (supplier instanceof ThrowingSupplier<?> throwableSupplier) {
				return throwableSupplier.getWithException();
			}
			//从生产者获取实例
			return supplier.get();
		} catch (Throwable ex) {
			if (ex instanceof BeansException beansException) {
				throw beansException;
			}
			throw new BeanCreationException(beanName,
					"Instantiation of supplied bean failed", ex);
		} finally {
			if (outerBean != null) {
				this.currentlyCreatedBean.set(outerBean);
			} else {
				this.currentlyCreatedBean.remove();
			}
		}
	}

	/**
	 * Overridden in order to implicitly register the currently created bean as
	 * dependent on further beans getting programmatically retrieved during a
	 * {@link Supplier} callback.
	 * 为了支持Java8的obtainFromSupplier方法
	 *
	 * @see #obtainFromSupplier
	 * @since 5.0
	 */
	@Override
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {
		//获取当前线程正在创建的bean的名称，主要是支持obtainFromSupplier方法，一般情况获取到的值都为null
		String currentlyCreatedBean = this.currentlyCreatedBean.get();
		//如果不为null
		if (currentlyCreatedBean != null) {
			registerDependentBean(beanName, currentlyCreatedBean);
		}
		//如果为null，一般走这一步逻辑，调用父类AbstractBeanFactory的同名方法
		return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
	}

	/**
	 * Determine candidate constructors to use for the given bean, checking all registered
	 * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
	 * <p>
	 * 通过检查所有已注册的SmartInstantiationAwareBeanPostProcessor后处理器，确定要用于给定 bean 的候选构造器
	 * 具体的检查逻辑是在SmartInstantiationAwareBeanPostProcessor后处理器的determineCandidateConstructors方法中
	 * <p>
	 * 注意这里是检查注解，比如@Autowired注解，因此需要开启注解支持，比如annotation-config或者component-scan
	 * 该类型的后置处理器的实现有两个：ConfigurationClassPostProcessor$ImportAwareBeanPostProcessor，AutowiredAnnotationBeanPostProcessor
	 * ConfigurationClassPostProcessor$ImportAwareBeanPostProcessor中什么也没干，因此具体的逻辑都在AutowiredAnnotationBeanPostProcessor中
	 * 所以说这个后置处理器的determineCandidateConstructors方法执行时机是在: 对象实例化之前执行
	 *
	 * @param beanClass the raw class of the bean
	 * @param beanName  the name of the bean
	 * @return the candidate constructors, or {@code null} if none specified
	 * @throws org.springframework.beans.BeansException in case of errors
	 * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
	 */
	@Nullable
	protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
			throws BeansException {

		if (beanClass != null && hasInstantiationAwareBeanPostProcessors()) {
			// 1.遍历所有的BeanPostProcessor
			for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
				// 2.调用SmartInstantiationAwareBeanPostProcessor的determineCandidateConstructors方法，
				// 该方法可以返回要用于beanClass的候选构造函数
				// 例如：使用@Autowire注解修饰构造函数，则该构造函数在这边会被AutowiredAnnotationBeanPostProcessor找到
				Constructor<?>[] ctors = bp.determineCandidateConstructors(beanClass, beanName);
				if (ctors != null) {
					// 3.如果ctors不为空，则不再继续执行其他的SmartInstantiationAwareBeanPostProcessor
					return ctors;
				}
			}
		}
		return null;
	}

	/**
	 * Instantiate the given bean using its default constructor.
	 * 使用其默认无参构造器实例化给定 bean
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @return a BeanWrapper for the new instance
	 */
	protected BeanWrapper instantiateBean(String beanName, RootBeanDefinition mbd) {
		try {
			//通用逻辑
			//getInstantiationStrategy，返回用于创建 bean 实例的实例化策略，就是instantiationStrategy属性
			//默认是CglibSubclassingInstantiationStrategy类型的实例，实现了SimpleInstantiationStrategy
			Object beanInstance = getInstantiationStrategy().instantiate(mbd, beanName, this);
			//新建BeanWrapperImpl，设置到内部属性中
			BeanWrapper bw = new BeanWrapperImpl(beanInstance);
			//主要是为当前的BeanWrapperImpl实例设置转换服务ConversionService以及注册自定义的属性编辑器PropertyEditor。
			initBeanWrapper(bw);
			return bw;
		} catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
	 * on a factory object itself configured using Dependency Injection.
	 * 使用命名工厂方法实例化bean。如果mbd参数指定了一个类，而不是factoryBean，
	 * 或者使用依赖注入配置的工厂对象本身的实例变量，则该方法可能是静态的。
	 *
	 * @param beanName     the name of the bean
	 * @param mbd          the bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 *                     or {@code null} if none (implying the use of constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 * @see #getBean(String, Object[])
	 */
	protected BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		//创建构造器处理器，并使用 factory method 进行实例化
		return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
	}

	/**
	 * <p>以自动注入方式调用最匹配的构造函数来实例化参数对象</p>
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>"autowire constructor"(按类型带有构造函数参数)的行为。如果显示指定了构造函数自变量值，
	 * 则将所有剩余自变量与Bean工厂中的Bean进行匹配时也适用</p>
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * <p>这对应于构造函数注入：在这种模式下，Spring Bean工厂能够托管需要基于构造函数数的
	 * 依赖关系解析的组件</p>
	 * @param beanName the name of the bean -- Bean名
	 * @param mbd the bean definition for the bean -- Bean的BeanDefinition
	 * @param ctors the chosen candidate constructors -- 选择的候选构造函数
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 *                     -- 用于构造函数或工厂方法调用的显示参数
	 * @return a BeanWrapper for the new instance -- 新实例的BeanWrapper
	 */
	protected BeanWrapper autowireConstructor(
			String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {
		//内部实际上是委托的ConstructorResolver构造器解析器的autowireConstructor方法来实现的
		return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
	}

	/**
	 * Populate the bean instance in the given BeanWrapper with the property values
	 * from the bean definition.
	 * 使用 bean 定义中的属性值在给定的 BeanWrapper 中填充 bean 实例，简单的说就是：
	 * setter方法和注解反射方式的依赖注入，有可能由于依赖其他bean而导致其他bean的初始化
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the bean definition for the bean
	 * @param bw       the BeanWrapper with bean instance
	 */
	protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
		/*
		 * 校验bw为null的情况
		 * 如果此bean定义中定义了<property>标签，那么抛出异常，其他情况则直接返回
		 */
		if (bw == null) {
			//如果mbd存在propertyValues属性，即定义了<property>标签
			//因为BeanWrapper都为null了，不能进行依赖注入，那么抛出异常
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			} else {
				// Skip property population phase for null instance.
				// 空对象直接返回
				return;
			}
		}

		// Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
		// state of the bean before properties are set. This can be used, for example,
		// to support styles of field injection.
		// 到这步的时候，bean 实例化完成（通过工厂方法或构造方法），但是还没开始属性设值，
		// InstantiationAwareBeanPostProcessor 的实现类可以在这里对 bean 进行状态修改，
		if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				// 如果返回 false，代表不需要进行后续的属性设值，也不需要再经过其他的 BeanPostProcessor 的处理
				if (!bp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
					return;
				}
			}
		}
		// pvs 是一个 MutablePropertyValues 实例，里面实现了PropertyValues接口，提供属性的读写操作实现，同时可以通过调用构造函数实现深拷贝
		PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);
		// 根据bean的依赖注入方式：即是否标注有 @Autowired 注解或 autowire=“byType/byName” 的标签
		// 会遍历bean中的属性，根据类型或名称来完成相应的注入
		int resolvedAutowireMode = mbd.getResolvedAutowireMode();
		if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
			MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
			// Add property values based on autowire by name if applicable.
			// 通过名字找到所有属性值，如果是 bean 依赖，先初始化依赖的 bean。记录依赖关系
			if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
				autowireByName(beanName, mbd, bw, newPvs);
			}
			// Add property values based on autowire by type if applicable.
			// 通过类型装配
			if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
				autowireByType(beanName, mbd, bw, newPvs);
			}
			// 结合注入后的配置，覆盖当前配置
			pvs = newPvs;
		}

		// 容器是否注册了InstantiationAwareBeanPostProcessor
		if (hasInstantiationAwareBeanPostProcessors()) {
			if (pvs == null) {
				pvs = mbd.getPropertyValues();
			}
			for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
				// 这里有个非常有用的 BeanPostProcessor 进到这里: AutowiredAnnotationBeanPostProcessor
				// 对采用 @Autowired、@Value 注解的依赖进行设值，
				PropertyValues pvsToUse = bp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
				if (pvsToUse == null) {
					return;
				}
				pvs = pvsToUse;
			}
		}
		// 是否进行依赖检查
		boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);
		if (needsDepCheck) {
			PropertyDescriptor[] filteredPds = filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
			// 检查是否满足相关依赖关系，对应的depends-on属性，需要确保所有依赖的Bean先完成初始化
			checkDependencies(beanName, mbd, filteredPds, pvs);
		}

		if (pvs != null) {
			// 设置 bean 实例的属性值
			applyPropertyValues(beanName, mbd, bw, pvs);
		}
	}

	/**
	 * <p> 通过bw的PropertyDescriptor属性名，查找出对应的Bean对象，将其添加到pvs中 </p>
	 * Fill in any missing property values with references to
	 * other beans in this factory if autowire is set to "byName".
	 * <p>如果autowire被设置为"byName"，则用对工厂中其他bean的引用填充任何缺失的属性值</p>
	 * @param beanName the name of the bean we're wiring up.
	 * Useful for debugging messages; not used functionally.
	 *                 -- 我们要连接的bean的名称。用于调试消息；未使用的功能
	 * @param mbd bean definition to update through autowiring
	 *             -- 通过自动装配来更新BeanDefinition
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 *           -- 我们可以从中获取关于bean的信息的BeanWrapper
	 * @param pvs the PropertyValues to register wired objects with
	 *            -- 要向其注册连接对象的 PropertyValues
	 */
	protected void autowireByName(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

		// 根据bw的PropertyDescriptors，遍历出所有可写的（即set方法存在)，存在于BeanDefinition里的PropertyValues，且不是简单属性的属性名
		// 简单属性的判定参照下面方法，主要涵盖基本类型及其包装类，Number,Date等
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		for (String propertyName : propertyNames) {
			// 检查缓存bean 中是否有当前bean
			if (containsBean(propertyName)) {
				// 递归初始化 bean，会调用 doGetBean() 来获取bean
				Object bean = getBean(propertyName);
				pvs.add(propertyName, bean);
				// 注册依赖，将依赖关系保存到 Map<String, Set<String>> dependentBeanMap dependentBeanMap 中，key是 bean，value是 转化后的 propertyName
				registerDependentBean(propertyName, beanName);
				if (logger.isTraceEnabled()) {
					logger.trace("Added autowiring by name from bean name '" + beanName +
							"' via property '" + propertyName + "' to bean named '" + propertyName + "'");
				}
			} else {
				// 找不到则不处理
				if (logger.isTraceEnabled()) {
					logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName +
							"' by name: no matching bean found");
				}
			}
		}
	}

	/**
	 * <p> 通过bw的PropertyDescriptor属性类型，查找出对应的Bean对象，将其添加到pvs中 </p>
	 * Abstract method defining "autowire by type" (bean properties by type) behavior.
	 * <p>定义 "按类型自动装配" (按类型bean属性)行为的抽象方法</p>
	 * <p>This is like PicoContainer default, in which there must be exactly one bean
	 * of the property type in the bean factory. This makes bean factories simple to
	 * configure for small namespaces, but doesn't work as well as standard Spring
	 * behavior for bigger applications.
	 * <p>这类似于PicoContainer默认值，其中bean工厂中必须恰好有一个属性类型的bean。这使得针对
	 * 小名称空间配置bean工厂变得简单，但是对于较大的应用程序，它的工作效果不如标准的Spring行为。</p>
	 * @param beanName the name of the bean to autowire by type -- 要按类型自动连接的bean的名称
	 * @param mbd the merged bean definition to update through autowiring
	 *            -- 合并后的BeanDefinition更新通过自动装配
	 * @param bw the BeanWrapper from which we can obtain information about the bean
	 *           -- 我们可以从中获取关于bean的信息的BeanWrapper
	 * @param pvs the PropertyValues to register wired objects with
	 *            -- propertyValue 注册连接对象
	 */
	protected void autowireByType(
			String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {
		//获取工厂的自定义类型转换器
		TypeConverter converter = getCustomTypeConverter();
		//如果没有配置自定义类型转换器
		if (converter == null) {
			//使用bw作为类型转换器
			converter = bw;
		}
		//存放所有候选Bean名的集合
		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
		//获取bw中有setter方法 && 非简单类型属性 && mbd的PropertyValues中没有该pd的属性名的 PropertyDescriptor 属性名数组
		String[] propertyNames = unsatisfiedNonSimpleProperties(mbd, bw);
		//遍历属性名数组
		for (String propertyName : propertyNames) {
			try {
				//PropertyDescriptor:表示JavaBean类通过存储器导出一个属性
				//从bw中获取propertyName对应的PropertyDescriptor
				PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
				// Don't try autowiring by type for type Object: never makes sense,
				// even if it technically is an unsatisfied, non-simple property.
				// 不要尝试按类型自动装配对象：永远是有意义的，即使它在技术上是一个不满意，复杂属性
				//如果pd的属性值类型不是 Object
				if (Object.class != pd.getPropertyType()) {
					//获取pd属性的Setter方法的方法参数包装对象
					MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
					// Do not allow eager init for type matching in case of a prioritized post-processor.
					// 在有优先级的后处理程序的情况下，不允许急于初始化来进行类型匹配。
					//PriorityOrdered:PriorityOrdered是个接口，继承自Ordered接口，未定义任何方法
					// -- 若对象o1是Ordered接口类型，o2是PriorityOrdered接口类型，那么o2的优先级高于o1
					// -- 若对象o1是PriorityOrdered接口类型，o2是Ordered接口类型，那么o1的优先级高于o2
					// -- 其他情况，若两者都是Ordered接口类型或两者都是PriorityOrdered接口类型，调用Ordered接口的getOrder方法得到order值，order值越大，优先级越小
					//判断bean对象是否是PriorityOrder实例，如果不是就允许急于初始化来进行类型匹配。
					//eager为true时会导致初始化lazy-init单例和由FactoryBeans(或带有"factory-bean"引用的工厂方法)创建 的对象以进行类型检查
					boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
					//AutowireByTypeDependencyDescriptor:根据类型依赖自动注入的描述符，重写了 getDependencyName() 方法，使其永远返回null
					//将 methodParam 封装包装成AutowireByTypeDependencyDescriptor对象
					DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
					//根据据desc的依赖类型解析出与descriptor所包装的对象匹配的候选Bean对象
					Object autowiredArgument = resolveDependency(desc, beanName, autowiredBeanNames, converter);
					//如果autowiredArgument不为null
					if (autowiredArgument != null) {
						//propertyName,autowireArgument作为键值添加到pvs中
						pvs.add(propertyName, autowiredArgument);
					}
					//遍历所有候选Bean名集合
					for (String autowiredBeanName : autowiredBeanNames) {
						//注册beanName与dependentBeanNamed的依赖关系
						registerDependentBean(autowiredBeanName, beanName);
						//打印跟踪日志
						if (logger.isTraceEnabled()) {
							// 通过属性类型自动装配从bean名'beanName'的属性名'propertyName'，bean名为'autowiredBeanName'
							logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
									propertyName + "' to bean named '" + autowiredBeanName + "'");
						}
					}
					//将候选Bean名集合情况
					autowiredBeanNames.clear();
				}
			} catch (BeansException ex) {
				//捕捉自动装配时抛出的Bean异常，重新抛出 不满足依赖异常
				throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
			}
		}
	}


	/**
	 * <p>获取bw中有setter方法 && 非简单类型属性 && mbd的PropertyValues中没有该pd的属性名的 PropertyDescriptor 属性名数组</p>
	 * Return an array of non-simple bean properties that are unsatisfied.
	 * These are probably unsatisfied references to other beans in the
	 * factory. Does not include simple properties like primitives or Strings.
	 * <p>返回一个不满足要求的非简单bean属性数组。这些可能是对工厂中其他bean的不满意的引用。不包括简单属性，
	 * 如原始或字符串</p>
	 * @param mbd the merged bean definition the bean was created with
	 *            -- 创建bean时适用的合并BeanDefinition
	 * @param bw the BeanWrapper the bean was created with
	 *            -- 创建bean时使用的bean保证其
	 * @return an array of bean property names -- bean属性名数组
	 * @see org.springframework.beans.BeanUtils#isSimpleProperty
	 */
	protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
		Set<String> result = new TreeSet<>();
		//获取自己定义的property的集合
		PropertyValues pvs = mbd.getPropertyValues();
		//获取BeanWrapper中的属性描述符数组。
		//请注意，这里的一个数组元素实际上是一个具有一个参数的setter方法转换之后的属性描述符
		//PropertyDescriptor的name属性是截取的"set"之后的部分，并进行了处理：如果至少开头两个字符是大写，那么就返回原截取的值，否则返回开头为小写的截取的值
		//PropertyDescriptor的propertyType属性是方法的参数类型
		PropertyDescriptor[] pds = bw.getPropertyDescriptors();
		//遍历数组
		for (PropertyDescriptor pd : pds) {
			//1 如果该描述符存在用于写属性的方法(即"set"开头的方法)
			//2 并且没有排除依赖类型检查（该类型没有在ignoredDependencyTypes和ignoredDependencyInterfaces两个忽略注入的集合中
			//  此前讲的忽略setter自动注入的扩展点就是这两个集合的控制的，可以通过ignoreDependencyType和ignoreDependencyInterface方法设置）
			//3 并且自己定义的property的集合没有该"属性名"，即所有的<property>标签的name属性不包括该"属性"的name
			//4 并且propertyType不是简单类型属性：基本类型及其包装类、Enum、String、CharSequence、Number、Date、Temporal、URI、URL、Locale、Class
			//  这些类型的单个对象或者数组都被称为简单类型。
			if (pd.getWriteMethod() != null && !isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
					!BeanUtils.isSimpleProperty(pd.getPropertyType())) {
				//将name添加到返回值集合中
				result.add(pd.getName());
			}
		}
		//转换为数组并返回
		return StringUtils.toStringArray(result);
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * <p>从给定的BeanWrapper提取一组经过筛选的PropertyDesciptor,排除忽略的依赖项或忽略项上的定义的属性</p>
	 * @param bw the BeanWrapper the bean was created with -- 创建bean时使用的 bean包装器
	 * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
	 *              -- 是否缓存过滤PropertyDescriptors bean 类
	 * @return the filtered PropertyDescriptors -- 过滤后的PropertyDesciptors
	 * @see #isExcludedFromDependencyCheck
	 * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
		//PropertyDescriptor类表示JavaBean类通过存储器导出一个属性
		PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
		if (filtered == null) {
			//从bw提取一组经过筛选的PropertyDescriptor，排除忽略的依赖项类型或在忽略的依赖项接口上定义的属性。
			filtered = filterPropertyDescriptorsForDependencyCheck(bw);
			if (cache) {
				//putIfAbsent：如果map集合中没有该key对应的值，则直接添加，并返回null;如果已经存在对应的值，则依旧为原来的值.并返回原来的值
				//将bw的bean实例的类型,filtered添加到filteredPropertyDescriptorsCache中
				PropertyDescriptor[] existing =
						this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
				//如果已经存在对应的值
				if (existing != null) {
					//引用存在对应的值，正常来说不会出现这钟情况，因为已经存在的情况，
					// 	-- 已经由该方法的第一行过滤掉了，除非出现并发调用该方法的情况，这个时候永远都会返回第一次的值，
					// 	-- 以保证在并发的情况下，返回的对象永远都是同一个对象
					filtered = existing;
				}
			}
		}
		return filtered;
	}

	/**
	 * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
	 * excluding ignored dependency types or properties defined on ignored dependency interfaces.
	 * <p>从给定的BeanWrapper提取一组经过筛选的propertyDesciptor，排除忽略的依赖项类型或在忽略的依赖项接口上定义的属性。</p>
	 * @param bw the beanwrapper the bean was created with
	 *           -- 创建bean时使用的 bean 包装器
	 * @return the filtered PropertyDescriptors -- 过滤后的PropertyDesciptors
	 * @see #isExcludedFromDependencyCheck
	 */
	protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
		//使用List包装bw的PropertyDesciptors元素
		List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
		//  删除 pds 中 由CGLIB定义的属性和类型与被忽略项的依赖类型匹配的属性，或者由被忽略的依赖接口定义的PropertyDescriptor
		pds.removeIf(this::isExcludedFromDependencyCheck);
		//将pds转换成数组
		return pds.toArray(new PropertyDescriptor[0]);
	}

	/**
	 * Determine whether the given bean property is excluded from dependency checks.
	 * <p>确定给定bean属性是否被排除在依赖项检查之外</p>
	 * <p>This implementation excludes properties defined by CGLIB and
	 * properties whose type matches an ignored dependency type or which
	 * are defined by an ignored dependency interface.
	 * <p>此实现排除了由CGLIB定义的属性和类型与被忽略项的依赖类型匹配的属性，或者由被忽略
	 * 的依赖接口定义的属性</p>
	 * @param pd the PropertyDescriptor of the bean property
	 *           --- bean 属性的 PropertyDescriptor
	 * @return whether the bean property is excluded -- bean属性是否被排除
	 * @see #ignoreDependencyType(Class)
	 * @see #ignoreDependencyInterface(Class)
	 */
	protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
		//pd的属性是CGLIB定义的属性 || 该工厂的忽略依赖类型列表中包含该pd的属性类型 || pd的属性是ignoredDependencyInterfaces里面的接口定义的方法
		return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
				this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
				AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
	}

	/**
	 * <p>检查依赖项：主要检查pd的setter方法需要赋值时,pvs中有没有满足其pd的需求的属性值可供其赋值。</p>
	 * Perform a dependency check that all properties exposed have been set,
	 * if desired. Dependency checks can be objects (collaborating beans),
	 * simple (primitives and String), or all (both).
	 * <p>如果需要，执行依赖项检查，以确定已设置了公开的所有属性。依赖项检查可以是对像(协作bean)，
	 * 简单(原语和字符串)或全部(两者都有)</p>
	 * @param beanName the name of the bean -- bean名
	 * @param mbd the merged bean definition the bean was created with
	 *            -- 合并后的BeanDefinition的bean创建
	 * @param pds the relevant property descriptors for the target bean
	 *            -- 相关的目标bean的属性描述符
	 * @param pvs the property values to be applied to the bean
	 *            -- 适合的bean属性值
	 * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
	 */
	protected void checkDependencies(
			String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
			throws UnsatisfiedDependencyException {
		//获取mbd的依赖检查代码，默认为DEPENDENCY_CHECK_NONE,不检查
		int dependencyCheck = mbd.getDependencyCheck();
		//遍历pds
		for (PropertyDescriptor pd : pds) {
			if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
				//simpleProperty:其属性类型为 primitive 或者 primitive包装器，枚举，字符串， 或 其他字符，数字，日期，时态，URI，URL，语言环境或类
				//如果pd的属性类型是"简单"类型
				boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
				// 是否不满足： (dependencyCheck为对所有属性检查) || (pd的属性类型是"简单"类型 && dependencyCheck为 对原始类型（基本类型，String，集合）检查)
				//  -- || (pd的属性类型不是"简单"类型 && 对依赖对象检查)
				boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
						(isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
						(!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
				//如果不满足
				if (unsatisfied) {
					//这个时候意味着 pd的setter方法是需要赋值,但是pvs中没有满足其pd的需求的属性值进行赋值
					//抛出不满足依赖异常：设置此属性值或禁用此bean依赖项检查
					throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
							"Set this property value or disable dependency checking for this bean.");
				}
			}
		}
	}

	/**
	 * Apply the given property values, resolving any runtime references
	 * to other beans in this bean factory. Must use deep copy, so we
	 * don't permanently modify this property.
	 * <p>应用给定的属性值，解决任何在这个bean工厂运行时其他bean的引用。必须使用深拷贝，所以我们
	 * 不会永久地修改这个属性</p>
	 * @param beanName the bean name passed for better exception information
	 *                 -- 传递bean名以获得更好的异常信息
	 * @param mbd the merged bean definition
	 *            -- 合并后的bean定义
	 * @param bw the BeanWrapper wrapping the target object
	 *           -- 包装目标对象的BeanWrapper
	 * @param pvs the new property values
	 *            -- 新得属性值
	 */
	protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
		if (pvs.isEmpty()) {
			return;
		}
		MutablePropertyValues mpvs = null;
		List<PropertyValue> original;
		// 1.获取属性值列表
		if (pvs instanceof MutablePropertyValues _mpvs) {
			mpvs = _mpvs;
			// 1.1 如果mpvs中的值已经被转换为对应的类型，那么可以直接设置到BeanWrapper中
			if (mpvs.isConverted()) {
				// Shortcut: use the pre-converted values as-is.
				try {
					bw.setPropertyValues(mpvs);
					return;
				} catch (BeansException ex) {
					throw new BeanCreationException(
							mbd.getResourceDescription(), beanName, "Error setting property values", ex);
				}
			}
			original = mpvs.getPropertyValueList();
		} else {
			// 1.2 如果pvs并不是使用MutablePropertyValues封装的类型，那么直接使用原始的属性获取方法
			original = Arrays.asList(pvs.getPropertyValues());
		}

		TypeConverter converter = getCustomTypeConverter();
		if (converter == null) {
			converter = bw;
		}
		// 2.1 获取对应的解析器
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

		// Create a deep copy, resolving any references for values.
		// 2.2 创建深层拷贝副本，用于存放解析后的属性值
		List<PropertyValue> deepCopy = new ArrayList<PropertyValue>(original.size());
		boolean resolveNecessary = false;
		// 3.遍历属性，将属性转换为对应类的对应属性的类型
		for (PropertyValue pv : original) {
			if (pv.isConverted()) {
				// 3.1 如果pv已经包含转换的值，则直接添加到deepCopy
				deepCopy.add(pv);
			} else {
				// 3.2 否则，进行转换
				// 3.2.1 拿到pv的原始属性名和属性值
				String propertyName = pv.getName();
				Object originalValue = pv.getValue();
				// 3.2.2 使用解析器解析原始属性值
				Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
				Object convertedValue = resolvedValue;
				// 3.2.3 判断该属性是否可转换
				boolean convertible = bw.isWritableProperty(propertyName) &&
						!PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
				if (convertible) {
					// 3.2.4 如果可转换，则转换指定目标属性的给定值
					convertedValue = convertForProperty(resolvedValue, propertyName, bw, converter);
				}
				// Possibly store converted value in merged bean definition,
				// in order to avoid re-conversion for every created bean instance.
				// 3.2.5 在合并的BeanDefinition中存储转换后的值，以避免为每个创建的bean实例重新转换
				if (resolvedValue == originalValue) {
					//如果可转换
					if (convertible) {
						//将convertedValue设置到pv中
						pv.setConvertedValue(convertedValue);
					}
					//将pv添加到deepCopy中
					deepCopy.add(pv);
				}
				//TypedStringValue:类型字符串的Holder,这个holder将只存储字符串值和目标类型。实际得转换将由Bean工厂执行
				//如果可转换 && originalValue是TypedStringValue的实例 && originalValue不是标记为动态【即不是一个表达式】&&
				// 	convertedValue不是Collection对象 或 数组
				else if (convertible && originalValue instanceof TypedStringValue typedStringValue &&
						!typedStringValue.isDynamic() &&
						!(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
					//将convertedValue设置到pv中
					pv.setConvertedValue(convertedValue);
					//将pv添加到deepCopy中
					deepCopy.add(pv);
				} else {
					//标记还需要解析
					resolveNecessary = true;
					//根据pv,convertedValue构建PropertyValue对象，并添加到deepCopy中
					deepCopy.add(new PropertyValue(pv, convertedValue));
				}
			}
		}
		//mpvs不为null && 已经不需要解析
		if (mpvs != null && !resolveNecessary) {
			//将此holder标记为只包含转换后的值
			mpvs.setConverted();
		}

		// Set our (possibly massaged) deep copy.
		try {
			// 4.设置bean的属性值为deepCopy
			bw.setPropertyValues(new MutablePropertyValues(deepCopy));
		} catch (BeansException ex) {
			//重新抛出Bean创建异常：错误设置属性值
			throw new BeanCreationException(
					mbd.getResourceDescription(), beanName, "Error setting property values", ex);
		}
	}

	/**
	 * Convert the given value for the specified target property.
	 * <p>给定的值转换为指定的目标属性对象</p>
	 */
	@Nullable
	private Object convertForProperty(
			@Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {
		//如果converter是BeanWrapperImpl实例
		if (converter instanceof BeanWrapperImpl beanWrapper) {
			return beanWrapper.convertForProperty(value, propertyName);
		} else {
			//获取 propertyName的属性描述符对象
			PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
			//获取pd的setter方法参数
			MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
			//将value转换为pd要求的属性类型对象
			return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
		}
	}


	/**
	 * Initialize the given bean instance, applying factory callbacks
	 * as well as init methods and bean post processors.
	 * <p>Called from {@link #createBean} for traditionally defined beans,
	 * and from {@link #initializeBean} for existing bean instances.
	 *
	 * @param beanName the bean name in the factory (for debugging purposes)
	 * @param bean     the new bean instance we may need to initialize
	 * @param mbd      the bean definition that the bean was created with
	 *                 (can also be {@code null}, if given an existing bean instance)
	 *
	 *                 <p>
	 *                 初始化给定的 bean 实例，主要是应用各种回调方法：
	 *                 1 回调一些特殊的Aware接口的方法，包括BeanNameAware、BeanClassLoaderAware、BeanFactoryAware
	 *                 2 回调所有BeanPostProcessor的postProcessBeforeInitialization方法，包括@PostConstruct注解标注的初始化方法
	 *                 3 回调所有配置的init-method方法，包括InitializingBean.afterPropertiesSet()和init-method
	 *                 4 回调所有BeanPostProcessor的postProcessAfterInitialization方法
	 * @return the initialized bean instance (potentially wrapped)
	 * @see BeanNameAware
	 * @see BeanClassLoaderAware
	 * @see BeanFactoryAware
	 * @see #applyBeanPostProcessorsBeforeInitialization
	 * @see #invokeInitMethods
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
		// 如果 bean 实现了 BeanNameAware、BeanClassLoaderAware 或 BeanFactoryAware 接口，回调
		invokeAwareMethods(beanName, bean);

		Object wrappedBean = bean;
		if (mbd == null || !mbd.isSynthetic()) {
			// BeanPostProcessor 的 postProcessBeforeInitialization 回调
			wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
		}

		try {
			// 处理 bean 中定义的 init-method，
			// 或者如果 bean 实现了 InitializingBean 接口，调用 afterPropertiesSet() 方法
			invokeInitMethods(beanName, wrappedBean, mbd);
		} catch (Throwable ex) {
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null), beanName, ex.getMessage(), ex);
		}
		if (mbd == null || !mbd.isSynthetic()) {
			// BeanPostProcessor 的 postProcessAfterInitialization 回调
			wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
		}

		return wrappedBean;
	}

	/**
	 * AbstractAutowireCapableBeanFactory的方法
	 * <p>
	 * 一些特殊的Aware接口的回调，顺序为（如果存在）：
	 * BeanNameAware.setBeanName(name)
	 * BeanClassLoaderAware.setBeanClassLoader(classLoader)
	 * BeanFactoryAware.setBeanFactory(beanFactory)
	 * <p>
	 * 这些Aware接口在此前的createBeanFactory方法中已经被加入到了忽略setter方法的自动装配的集合ignoredDependencyInterfaces中
	 *
	 * @param beanName beanName
	 * @param bean     bean实例
	 */
	private void invokeAwareMethods(String beanName, Object bean) {
		/*如果属于Aware接口*/
		if (bean instanceof Aware) {
			/*
			 * 1 如果属于BeanNameAware，那么回调setBeanName方法，将beanName作为参数
			 */
			if (bean instanceof BeanNameAware beanNameAware) {
				beanNameAware.setBeanName(beanName);
			}
			/*
			 * 2 如果属于BeanClassLoaderAware，那么回调setBeanClassLoader方法，将ClassLoader作为参数
			 */
			if (bean instanceof BeanClassLoaderAware beanClassLoaderAware) {
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					beanClassLoaderAware.setBeanClassLoader(bcl);
				}
			}
			/*
			 * 3 如果属于BeanFactoryAware，那么回调setBeanFactory方法，将当前beanFactory作为参数
			 */
			if (bean instanceof BeanFactoryAware beanFactoryAware) {
				beanFactoryAware.setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}

	/**
	 * Give a bean a chance to react now all its properties are set,
	 * and a chance to know about its owning bean factory (this object).
	 * This means checking whether the bean implements InitializingBean or defines
	 * a custom init method, and invoking the necessary callback(s) if it does.
	 * 回调自定义的initMethod初始化方法，顺序为：
	 * 1 InitializingBean.afterPropertiesSet()方法
	 * 2 XML配置的init-method方法
	 *
	 * @param beanName the bean name in the factory (for debugging purposes)
	 *                 工厂中的bean名称（用于调试目的）
	 * @param bean     the new bean instance we may need to initialize
	 *                 可能需要初始化的新 bean 实例
	 * @param mbd      the merged bean definition that the bean was created with
	 *                 (can also be {@code null}, if given an existing bean instance)
	 *                 创建 bean 的 bean 定义（如果给定现有 bean 实例，也可以为 null）
	 * @throws Throwable if thrown by init methods or by the invocation process
	 * @see #invokeCustomInitMethod
	 */
	protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
			throws Throwable {

		//bean实例是否属于InitializingBean
		boolean isInitializingBean = (bean instanceof InitializingBean);
		/*
		 * 1 如果属于InitializingBean
		 * 2 并且externallyManagedInitMethods集合中不存在afterPropertiesSet方法
		 *   前面的在LifecycleMetadata的checkedInitMethods方法中我们就知道，通过@PostConstruct标注的方法会被存入externallyManagedInitMethods中
		 *   如果此前@PostConstruct注解标注了afterPropertiesSet方法，那么这个方法不会被再次调用，这就是externallyManagedInitMethods防止重复调用的逻辑
		 */
		if (isInitializingBean && (mbd == null || !mbd.hasAnyExternallyManagedInitMethod("afterPropertiesSet"))) {
			if (logger.isTraceEnabled()) {
				logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
			}
			//回调afterPropertiesSet方法
			((InitializingBean) bean).afterPropertiesSet();
		}

		if (mbd != null && bean.getClass() != NullBean.class) {
			//获取initMethodName属性，就是XML的init-method属性
			String[] initMethodNames = mbd.getInitMethodNames();
			if (initMethodNames != null) {
				for (String initMethodName : initMethodNames) {
					/*
					 * 1 如果存在init-method方法
					 * 2 并且不是InitializingBean类型或者是InitializingBean类型但是initMethodName不是"afterPropertiesSet"方法
					 *   这里也是防止重复调用同一个方法的逻辑，因为在上面会调用afterPropertiesSet方法，这里不必再次调用
					 * 3 并且externallyManagedInitMethods集合中不存在该方法
					 *   在LifecycleMetadata的checkedInitMethods方法中我们就知道，通过@PostConstruct标注的方法会被存入externallyManagedInitMethods中
					 *   如果此前@PostConstruct注解标注了afterPropertiesSet方法，那么这个方法不会被再次调用，这就是externallyManagedInitMethods防止重复调用的逻辑
					 */
					if (StringUtils.hasLength(initMethodName) &&
							!(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
							!mbd.hasAnyExternallyManagedInitMethod(initMethodName)) {
						//反射回调init-method的方法
						invokeCustomInitMethod(beanName, bean, mbd, initMethodName);
					}
				}
			}
		}
	}

	/**
	 * Invoke the specified custom init method on the given bean.
	 * Called by invokeInitMethods.
	 * <p>在给定的Bean上调用指定的自定义init方法</p>
	 * <p>Can be overridden in subclasses for custom resolution of init
	 * methods with arguments.
	 * <p>可以在子类中覆盖初始化方法的自定义解析</p>
	 * @see #invokeInitMethods
	 */
	protected void invokeCustomInitMethod(String beanName, Object bean, RootBeanDefinition mbd, String initMethodName)
			throws Throwable {

		//	BeanUtils.findMethod(bean.getClass(), initMethodName)：找到一个具有给定方法名和给定参数类型的方法，该方法在给定
		//		类或其父类中声明。首选 公共方法，但也将返回受保护的包访问或私有方法。
		// ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName))：确定给定的类是否具有带有给定签名的公共方法，
		// 		并返回(如果可用)(否则返回null).
		//根据mbd是否允许访问非公共构造函数和方法的权限获取初始化方法对象
		Method initMethod = (mbd.isNonPublicAccessAllowed() ?
				BeanUtils.findMethod(bean.getClass(), initMethodName) :
				ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));
		//如果初始化方法为null
		if (initMethod == null) {
			//如 mbd 指示配置的init方法为默认方法,一般指定的初始化方法名 isEnforceInitMethod() 就会为true
			if (mbd.isEnforceInitMethod()) {
				//抛出 BeanDefinition非法异常：无法找到名为'initMethodName'的初始化方法在bean名为'beanName'中
				throw new BeanDefinitionValidationException("Could not find an init method named '" +
						initMethodName + "' on bean with name '" + beanName + "'");
			} else {
				//如果当前日志级别为跟踪
				if (logger.isTraceEnabled()) {
					//打印跟踪日志：没有名为'initMethodName'默认初始化方法在名为'beanName'中找到
					logger.trace("No default init method named '" + initMethodName +
							"' found on bean with name '" + beanName + "'");
				}
				// Ignore non-existent default lifecycle methods.
				// 忽略不存在的默认生命周期方法
				return;
			}
		}

		//如果当前日志级别为跟踪
		if (logger.isTraceEnabled()) {
			//调用名为'initMethodName'默认初始化方法在名为'beanName'中
			logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
		}
		//获取method相应的接口方法对象，如果找不到，则返回原始方法
		Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod, bean.getClass());

		try {
			// methodToInvoke 可访问，在需要时设置它的可访问性
			ReflectionUtils.makeAccessible(methodToInvoke);
			//调用 bean的methodToInvoke方法
			methodToInvoke.invoke(bean);
		} catch (InvocationTargetException ex) {
			//InvocationTargetException：当被调用的方法的内部抛出了异常而没有被捕获时，将由此异常接收！！！
			//获取其目标异常，重新抛出
			throw ex.getTargetException();
		}
	}


	/**
	 * Applies the {@code postProcessAfterInitialization} callback of all
	 * registered BeanPostProcessors, giving them a chance to post-process the
	 * object obtained from FactoryBeans (for example, to auto-proxy them).
	 * <p>应用所有已注册BeanPostProcessor的postProcessAfterInitalization回调,使它
	 * 们有机会对FactoryBeans获得的对象进行后处理(例如,自动代理它们)</p>
	 * @see #applyBeanPostProcessorsAfterInitialization
	 */
	@Override
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		//初始化后的后处理
		return applyBeanPostProcessorsAfterInitialization(object, beanName);
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 * <p>也被重新以清除FactoryBean实例缓存</p>
	 */
	@Override
	protected void removeSingleton(String beanName) {
		//获取单例互斥体，一般使用singletonObjects
		synchronized (getSingletonMutex()) {
			//1. 从该工厂单例缓存中删除具有给定名称的Bean。如果创建失败，则能够清理饿汉式注册 的单例
			//2. 重写以清除FactoryBean对象缓存
			super.removeSingleton(beanName);
			//factoryBeanInstanceCache:未完成的FactoryBean实例的高速缓存
			//删除beanName对应的factoryBean对象
			this.factoryBeanInstanceCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear FactoryBean instance cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanInstanceCache.clear();
		}
	}

	/**
	 * Expose the logger to collaborating delegates.
	 *
	 * @since 5.0.7
	 */
	Log getLogger() {
		return logger;
	}


	/**
	 * <p>根据类型依赖自动注入的描述符，重写了 {@link #getDependencyName()} 方法，使其永远返回null</p>
	 * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
	 * Always optional; never considering the parameter name for choosing a primary candidate.
	 * <p>Spring 古老的 autowire = 'byType' 模式的特殊依赖描述符变体。总是可选；在选择主要候选对象时从不考虑
	 * 参数名称</p>
	 */
	@SuppressWarnings("serial")
	private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

		public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
			super(methodParameter, false, eager);
		}

		@Override
		public String getDependencyName() {
			return null;
		}
	}


	/**
	 * {@link MethodCallback} used to find {@link FactoryBean} type information.
	 */
	private static class FactoryBeanMethodTypeFinder implements MethodCallback {

		private final String factoryMethodName;

		private ResolvableType result = ResolvableType.NONE;

		FactoryBeanMethodTypeFinder(String factoryMethodName) {
			this.factoryMethodName = factoryMethodName;
		}

		@Override
		public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
			if (isFactoryBeanMethod(method)) {
				ResolvableType returnType = ResolvableType.forMethodReturnType(method);
				ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
				if (this.result == ResolvableType.NONE) {
					this.result = candidate;
				} else {
					Class<?> resolvedResult = this.result.resolve();
					Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
					if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
						this.result = ResolvableType.forClass(commonAncestor);
					}
				}
			}
		}

		private boolean isFactoryBeanMethod(Method method) {
			return (method.getName().equals(this.factoryMethodName) &&
					FactoryBean.class.isAssignableFrom(method.getReturnType()));
		}

		ResolvableType getResult() {
			Class<?> resolved = this.result.resolve();
			boolean foundResult = resolved != null && resolved != Object.class;
			return (foundResult ? this.result : ResolvableType.NONE);
		}
	}

}
