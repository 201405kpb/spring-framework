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

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import jakarta.inject.Provider;

import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.core.OrderComparator;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.log.LogMessage;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.CompositeIterator;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Spring's default implementation of the {@link ConfigurableListableBeanFactory}
 * and {@link BeanDefinitionRegistry} interfaces: a full-fledged bean factory
 * based on bean definition metadata, extensible through post-processors.
 * <p>ConfigurableListableBeanFactory和BeanDefinitionRegistry接口的Spring默认实现:
 * 一个基于bean定义元数据的成熟的bean工厂，可通过后处理器扩展。</p>
 *
 * <p>Typical usage is registering all bean definitions first (possibly read
 * from a bean definition file), before accessing beans. Bean lookup by name
 * is therefore an inexpensive operation in a local bean definition table,
 * operating on pre-resolved bean definition metadata objects.
 * <p>典型的用法是在访问bean之前先注册所有bean定义(可能从bean定义文件中读取)。
 * 因此，按名称查找是本地Bean定义表中的一种廉价操作，对预解析的Bean定义元数据对象进行操作。</p>
 *
 * <p>Note that readers for specific bean definition formats are typically
 * implemented separately rather than as bean factory subclasses: see for example
 * {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}.
 * <p>请注意，特定bean定义格式的阅读器通常是单独实现的，而不是作为bean工厂的子类实现的:参见示例
 * PropertiesBeanDefinitionReader 和 org.springframework.beans.factory.xml.XmlBeanDefinitionReader.
 * </p>
 *
 * <p>For an alternative implementation of the
 * {@link org.springframework.beans.factory.ListableBeanFactory} interface,
 * have a look at {@link StaticListableBeanFactory}, which manages existing
 * bean instances rather than creating new ones based on bean definitions.
 * <p>用于org.springframework.bean.factory.ListableBeanFactory接口的替代实现。
 * 看一下StaticListableBeanFactory，它管理现有的bean实例，而不是根据bean定义创建新的实例</p>
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @see #registerBeanDefinition
 * @see #addBeanPostProcessor
 * @see #getBean
 * @see #resolveDependency
 * @since 16 April 2001
 */
@SuppressWarnings("serial")
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {

	/**
	 * jakarta.inject.Provider class 对象
	 */
	@Nullable
	private static Class<?> javaxInjectProviderClass;

	static {
		try {
			//使用当前类的类加载器加载jakarta.inject.Provider的class对象
			javaxInjectProviderClass =
					ClassUtils.forName("jakarta.inject.Provider", DefaultListableBeanFactory.class.getClassLoader());
		} catch (ClassNotFoundException ex) {
			// JSR-330 API not available - Provider interface simply not supported then.
			//JSR-330 API不可用-那时根本不支持提供程序接口
			//加载不了时，让javaxInjectProviderClass置为null，以确保明确显示没有jakarta.inject.Provider的相关jar包
			javaxInjectProviderClass = null;
		}
	}


	/**
	 * Map from serialized id to factory instance.
	 */
	private static final Map<String, Reference<DefaultListableBeanFactory>> serializableFactories =
			new ConcurrentHashMap<>(8);

	/**
	 * Optional id for this factory, for serialization purposes.
	 * 从反序列化的ID 映射到 工厂实例
	 */
	@Nullable
	private String serializationId;

	/**
	 * Whether to allow re-registration of a different definition with the same name.
	 * 是否允许重新注册具有相同名的不同定义
	 **/
	private boolean allowBeanDefinitionOverriding = true;

	/**
	 * Whether to allow eager class loading even for lazy-init beans.
	 */
	private boolean allowEagerClassLoading = true;

	/**
	 * Optional OrderComparator for dependency Lists and arrays.
	 * 依赖项列表和数组的可选OrderComparator
	 **/
	@Nullable
	private Comparator<Object> dependencyComparator;

	/**
	 * Resolver to use for checking if a bean definition is an autowire candidate.
	 * 用于检查beanDefinition是否为自动装配候选的解析程序
	 */
	private AutowireCandidateResolver autowireCandidateResolver = SimpleAutowireCandidateResolver.INSTANCE;

	/**
	 * <p>存放着手动显示注册的依赖项类型-相应的自动装配值的缓存</p>
	 * <p>手动显示注册指直接调用{@link #registerResolvableDependency(Class, Object)}</p>
	 * Map from dependency type to corresponding autowired value.
	 * <p>从依赖项类型映射到相应的自动装配值</p>
	 */
	private final Map<Class<?>, Object> resolvableDependencies = new ConcurrentHashMap<>(16);

	/**
	 * Map of bean definition objects, keyed by bean name.
	 * <p>Bean定义对象的映射，以Bean名称为键</p>
	 */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);

	/**
	 * Map from bean name to merged BeanDefinitionHolder.
	 */
	private final Map<String, BeanDefinitionHolder> mergedBeanDefinitionHolders = new ConcurrentHashMap<>(256);

	/**
	 * Map of singleton and non-singleton bean names, keyed by dependency type.
	 * <p>单例和非单例Bean名称的映射，按依赖项类型进行键控</p>
	 * <p>键控：在这里应该是指map的key/value形式的映射</p>
	 */
	private final Map<Class<?>, String[]> allBeanNamesByType = new ConcurrentHashMap<>(64);

	/**
	 * Map of singleton-only bean names, keyed by dependency type.
	 * <p>仅依赖单例的bean名称的映射，按依赖项类型进行键控</p>
	 */
	private final Map<Class<?>, String[]> singletonBeanNamesByType = new ConcurrentHashMap<>(64);

	/**
	 * List of bean definition names, in registration order.
	 * <p>BeanDefinition名称列表，按注册顺序，BeanDefinition名称与Bean名相同</p>
	 */
	private volatile List<String> beanDefinitionNames = new ArrayList<>(256);

	/**
	 * List of names of manually registered singletons, in registration order.
	 * <p>手动注册单例的名称列表，按注册顺序</p>
	 */
	private volatile Set<String> manualSingletonNames = new LinkedHashSet<>(16);

	/**
	 * Cached array of bean definition names in case of frozen configuration.
	 * <p>缓存的BeanDefinition名称数组，以防配置被冻结</p>
	 */
	@Nullable
	private volatile String[] frozenBeanDefinitionNames;

	/**
	 * <p>冻结配置的标记</p>
	 * Whether bean definition metadata may be cached for all beans.
	 * 是否可以为所有bean缓存bean定义元数据
	 */
	private volatile boolean configurationFrozen;


	/**
	 * Create a new DefaultListableBeanFactory.
	 * <p> 创建一个新的 DefaultListableBeanFactory </p>
	 */
	public DefaultListableBeanFactory() {
		super();
	}

	/**
	 * Create a new DefaultListableBeanFactory with the given parent.
	 * <p>使用给定的父级Bean工厂创建一个新的DefaultListableBeanFactory</p>
	 *
	 * @param parentBeanFactory the parent BeanFactory -- 父级bean工厂
	 */
	public DefaultListableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		super(parentBeanFactory);
	}


	/**
	 * <p>更新并保存序列化ID与当前工厂实例的映射关系</p>
	 * Specify an id for serialization purposes, allowing this BeanFactory to be
	 * deserialized from this id back into the BeanFactory object, if needed.
	 * <p>指定一个ID以进行序列化，如果需要的话，允许该BeanFactory从该ID反序列化为BeanFactory
	 * 对象</p>
	 */
	public void setSerializationId(@Nullable String serializationId) {
		//如果传入的序列化ID不为null
		if (serializationId != null) {
			//使用弱类型引用包装当前Bean工厂，再将映射关系添加到serializableFactories中
			serializableFactories.put(serializationId, new WeakReference<>(this));
		}
		//如果当前bean工厂的序列化ID不为null
		else if (this.serializationId != null) {
			//从serializableFactories中移除该serializationId对应的映射关系
			serializableFactories.remove(this.serializationId);
		}
		//对当前bean工厂的序列化ID重新赋值为传入的序列化ID
		this.serializationId = serializationId;
	}

	/**
	 * Return an id for serialization purposes, if specified, allowing this BeanFactory
	 * to be deserialized from this id back into the BeanFactory object, if needed.
	 *
	 * @since 4.1.2
	 */
	@Nullable
	public String getSerializationId() {
		return this.serializationId;
	}

	/**
	 * Set whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * If not, an exception will be thrown. This also applies to overriding aliases.
	 * <p>通过注册一个具有相同名称的不同定义(自动替换前一个定义)来设置是否允许它覆盖bean
	 * 定义。如果没有，将引发异常。这也适用于覆盖别名。</p>
	 *
	 * <p>Default is "true".
	 * <p>默认为true</p>
	 *
	 * @see #registerBeanDefinition
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
	}

	/**
	 * Return whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 *
	 * @since 4.1.2
	 */
	public boolean isAllowBeanDefinitionOverriding() {
		return this.allowBeanDefinitionOverriding;
	}

	/**
	 * Set whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>Default is "true". Turn this flag off to suppress class loading
	 * for lazy-init beans unless such a bean is explicitly requested.
	 * In particular, by-type lookups will then simply ignore bean definitions
	 * without resolved class name, instead of loading the bean classes on
	 * demand just to perform a type check.
	 *
	 * @see AbstractBeanDefinition#setLazyInit
	 */
	public void setAllowEagerClassLoading(boolean allowEagerClassLoading) {
		this.allowEagerClassLoading = allowEagerClassLoading;
	}

	/**
	 * Return whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>返回是否允许急切加载Bean类，即使对于标记为'lazy-init'的bean定义
	 * 也是如此</p>
	 *
	 * @since 4.1.2
	 */
	public boolean isAllowEagerClassLoading() {
		return this.allowEagerClassLoading;
	}

	/**
	 * Set a {@link java.util.Comparator} for dependency Lists and arrays.
	 *
	 * @see org.springframework.core.OrderComparator
	 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
	 * @since 4.0
	 */
	public void setDependencyComparator(@Nullable Comparator<Object> dependencyComparator) {
		this.dependencyComparator = dependencyComparator;
	}

	/**
	 * Return the dependency comparator for this BeanFactory (may be {@code null}.
	 * <p>返回此BeanFactory的依赖关系比较器(可以为null)</p>
	 *
	 * @since 4.0
	 */
	@Nullable
	public Comparator<Object> getDependencyComparator() {
		return this.dependencyComparator;
	}

	/**
	 * Set a custom autowire candidate resolver for this BeanFactory to use
	 * when deciding whether a bean definition should be considered as a
	 * candidate for autowiring.
	 */
	public void setAutowireCandidateResolver(AutowireCandidateResolver autowireCandidateResolver) {
		Assert.notNull(autowireCandidateResolver, "AutowireCandidateResolver must not be null");
		if (autowireCandidateResolver instanceof BeanFactoryAware beanFactoryAware) {
			beanFactoryAware.setBeanFactory(this);
		}
		this.autowireCandidateResolver = autowireCandidateResolver;
	}

	/**
	 * Return the autowire candidate resolver for this BeanFactory (never {@code null}).
	 * <p>返回此BeanFactory的自动装配候选解析器(永远不为 null ),默认使用SimpleAutowireCandidateResolver</p>
	 */
	public AutowireCandidateResolver getAutowireCandidateResolver() {
		return this.autowireCandidateResolver;
	}


	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof DefaultListableBeanFactory otherListableFactory) {
			this.allowBeanDefinitionOverriding = otherListableFactory.allowBeanDefinitionOverriding;
			this.allowEagerClassLoading = otherListableFactory.allowEagerClassLoading;
			this.dependencyComparator = otherListableFactory.dependencyComparator;
			// A clone of the AutowireCandidateResolver since it is potentially BeanFactoryAware
			setAutowireCandidateResolver(otherListableFactory.getAutowireCandidateResolver().cloneIfNecessary());
			// Make resolvable dependencies (e.g. ResourceLoader) available here as well
			this.resolvableDependencies.putAll(otherListableFactory.resolvableDependencies);
		}
	}


	//---------------------------------------------------------------------
	// Implementation of remaining BeanFactory methods
	//---------------------------------------------------------------------

	@Override
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		return getBean(requiredType, (Object[]) null);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T getBean(Class<T> requiredType, @Nullable Object... args) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		Object resolved = resolveBean(ResolvableType.forRawClass(requiredType), args, false);
		if (resolved == null) {
			throw new NoSuchBeanDefinitionException(requiredType);
		}
		return (T) resolved;
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType) {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType), true);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType) {
		return getBeanProvider(requiredType, true);
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	//---------------------------------------------------------------------

	/**
	 * 本地工厂中BeanDefinition对象映射【beanDefinitionMap】中是否存在beanName该键名
	 *
	 * @param beanName the name of the bean to look for -- 要寻找的bean名
	 */
	@Override
	public boolean containsBeanDefinition(String beanName) {
		//如果beanName为null，抛出异常
		Assert.notNull(beanName, "Bean name must not be null");
		//从beanDefinitionMap定义对象映射中判断beanName是否存在该键
		return this.beanDefinitionMap.containsKey(beanName);
	}

	@Override
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	@Override
	public String[] getBeanDefinitionNames() {
		String[] frozenNames = this.frozenBeanDefinitionNames;
		if (frozenNames != null) {
			return frozenNames.clone();
		} else {
			return StringUtils.toStringArray(this.beanDefinitionNames);
		}
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType, boolean allowEagerInit) {
		Assert.notNull(requiredType, "Required type must not be null");
		return getBeanProvider(ResolvableType.forRawClass(requiredType), allowEagerInit);
	}

	@Override
	public <T> ObjectProvider<T> getBeanProvider(ResolvableType requiredType, boolean allowEagerInit) {
		return new BeanObjectProvider<>() {
			@Override
			public T getObject() throws BeansException {
				T resolved = resolveBean(requiredType, null, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}

			@Override
			public T getObject(Object... args) throws BeansException {
				T resolved = resolveBean(requiredType, args, false);
				if (resolved == null) {
					throw new NoSuchBeanDefinitionException(requiredType);
				}
				return resolved;
			}

			@Override
			@Nullable
			public T getIfAvailable() throws BeansException {
				try {
					return resolveBean(requiredType, null, false);
				} catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}

			@Override
			public void ifAvailable(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfAvailable();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					} catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}

			@Override
			@Nullable
			public T getIfUnique() throws BeansException {
				try {
					return resolveBean(requiredType, null, true);
				} catch (ScopeNotActiveException ex) {
					// Ignore resolved bean in non-active scope
					return null;
				}
			}

			@Override
			public void ifUnique(Consumer<T> dependencyConsumer) throws BeansException {
				T dependency = getIfUnique();
				if (dependency != null) {
					try {
						dependencyConsumer.accept(dependency);
					} catch (ScopeNotActiveException ex) {
						// Ignore resolved bean in non-active scope, even on scoped proxy invocation
					}
				}
			}

			@SuppressWarnings("unchecked")
			@Override
			public Stream<T> stream() {
				return Arrays.stream(getBeanNamesForTypedStream(requiredType, allowEagerInit))
						.map(name -> (T) getBean(name))
						.filter(bean -> !(bean instanceof NullBean));
			}

			@SuppressWarnings("unchecked")
			@Override
			public Stream<T> orderedStream() {
				String[] beanNames = getBeanNamesForTypedStream(requiredType, allowEagerInit);
				if (beanNames.length == 0) {
					return Stream.empty();
				}
				Map<String, T> matchingBeans = CollectionUtils.newLinkedHashMap(beanNames.length);
				for (String beanName : beanNames) {
					Object beanInstance = getBean(beanName);
					if (!(beanInstance instanceof NullBean)) {
						matchingBeans.put(beanName, (T) beanInstance);
					}
				}
				Stream<T> stream = matchingBeans.values().stream();
				return stream.sorted(adaptOrderComparator(matchingBeans));
			}
		};
	}

	@Nullable
	private <T> T resolveBean(ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) {
		NamedBeanHolder<T> namedBean = resolveNamedBean(requiredType, args, nonUniqueAsNull);
		if (namedBean != null) {
			return namedBean.getBeanInstance();
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof DefaultListableBeanFactory dlfb) {
			return dlfb.resolveBean(requiredType, args, nonUniqueAsNull);
		} else if (parent != null) {
			ObjectProvider<T> parentProvider = parent.getBeanProvider(requiredType);
			if (args != null) {
				return parentProvider.getObject(args);
			} else {
				return (nonUniqueAsNull ? parentProvider.getIfUnique() : parentProvider.getIfAvailable());
			}
		}
		return null;
	}

	private String[] getBeanNamesForTypedStream(ResolvableType requiredType, boolean allowEagerInit) {
		return BeanFactoryUtils.beanNamesForTypeIncludingAncestors(this, requiredType, true, allowEagerInit);
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,也包括Prototype级别的Bean对象，
	 * 并允许初始化lazy-init单例和由FactoryBeans创建的对象
	 *
	 * @param type the genericallmatchy typed class or interface to -- 通用化allmatchy类型化的类或接口
	 * @return 匹配给定对象类型（包含子类）的bean（或由FactoryBean创建的对象）的名称；
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type) {
		//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,也包括Prototype级别的Bean对象，并允许初始化lazy-init单例
		// 和由FactoryBeans创建的对象
		return getBeanNamesForType(type, true, true);
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称:
	 * <ol>
	 *  <li>从type中解析出Class对象【变量 resolved 】</li>
	 *  <li>如果resolved不为null 且 没有包含泛型参数,调用 {@link #getBeanNamesForType(Class, boolean, boolean)} 来
	 *  获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称并返回出去</li>
	 *  <li>否则,调用 {@link #doGetBeanNamesForType(ResolvableType, boolean, boolean)} 来
	 *  获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称并返回出去</li>
	 * </ol>
	 *
	 * @param type                 the generically typed class or interface to match -- 要匹配的通用类型的类或接口
	 * @param includeNonSingletons whether to include prototype or scoped beans too
	 *                             or just singletons (also applies to FactoryBeans)
	 *                             -- 是否也包括原型或作用域bean或仅包含单例（也适用于FactoryBeans)
	 * @param allowEagerInit       whether to initialize <i>lazy-init singletons</i> and
	 *                             <i>objects created by FactoryBeans</i> (or by factory methods with a
	 *                             "factory-bean" reference) for the type check. Note that FactoryBeans need to be
	 *                             eagerly initialized to determine their type: So be aware that passing in "true"
	 *                             for this flag will initialize FactoryBeans and "factory-bean" references.
	 *                             -- 是否初始化 lazy-init单例和由FactoryBeans创建的对象(或通过带有'factory-bean'引用的
	 *                             工厂创建方法）创建类型检查。注意，需要饿汉式初始化FactoryBeans以确定他们的类型：因此
	 *                             请注意，为此标记传递 'true' 将初始化FactoryBean和'factory-bean'引用
	 * @return 匹配给定对象类型（包含子类）的bean（或由FactoryBean创建的对象）的名称；如果没有，则 返回一个空数组。
	 */
	@Override
	public String[] getBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		//从type中解析出Class对象
		Class<?> resolved = type.resolve();
		//如果resolved不为null 且 没有包含泛型参数
		if (resolved != null && !type.hasGenerics()) {
			//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称并返回出去
			return getBeanNamesForType(resolved, includeNonSingletons, includeNonSingletons);
		} else {
			//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称并返回出去
			return doGetBeanNamesForType(type, includeNonSingletons, includeNonSingletons);
		}
		// getBeanNamesForType方法虽然也会调用doGetBeanNamesForType去解析有泛型的情况，但是getBeanNamesForType
		// 优先读取缓存，在能确定其匹配类型的情况下(即没有泛型)的情况下，getBeanNamesForType方法的性能优于
		// doGetBeanNamesForType方法。
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称:
	 * <ol>
	 *   <li>实际调用getBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean allowEagerInit)并
	 * 	 返回其结果</li>
	 * 	 <li>includeNonSingletons设置为true，包含非单例</li>
	 * 	 <li>allowEagerInit设置为true允许初始化FactoryBean和'factory-bean'引用</li>
	 * </ol>
	 *
	 * @param type the class or interface to match, or {@code null} for all bean names
	 *             -- 要匹配的类或接口，或者使用{@code null}为所有的bean名称
	 * @return
	 */
	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type) {
		return getBeanNamesForType(type, true, true);
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称
	 * <ol>
	 *   <li>如果该工厂的配置冻结了 或者 要匹配的类型或接口为null 或者 不允许初始化FactoryBean和'factory-bean'引用,
	 *    就调用 doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit)来
	 *    获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,然后返回出去
	 *   </li>
	 *   <li>定义一个Class-String[]的Map缓存，如果包含是否包含非单例，就引用单例和非单例Bean名称的映射Map【{@link #allBeanNamesByType}】；否则
	 *   引用仅依赖单例的bean名称的映射Map【{@link #singletonBeanNamesByType}】</li>
	 *   <li>从缓存中获取type对应的BeanName数组 【变量 resolvedBeanNames】,如果获取成功就返回出去</li>
	 *   <li>调用doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit)来
	 *   获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,然后返回出去
	 *   </li>
	 *   <li>获取工厂的类加载器，如果该类加载器加载过type就将type与属于type的Bean类的所有Bean名称的映射关系添加到cache中</li>
	 *   <li>返回属于type的Bean类的所有Bean名称</li>
	 * </ol>
	 *
	 * @param type                 要匹配的通用类型的类或接口
	 * @param includeNonSingletons 是否包含非单例
	 * @param allowEagerInit       是否初始化FactoryBean和'factory-bean'引用
	 * @return 匹配给定对象类型（包含子类）的bean（或由FactoryBean创建的对象）的名称；如果没有，则 返回一个空数组。
	 * @see #doGetBeanNamesForType(ResolvableType, boolean, boolean)
	 */
	@Override
	public String[] getBeanNamesForType(@Nullable Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		//如果该工厂的配置冻结了 或者 要匹配的类型或接口为null 或者 不允许初始化FactoryBean和'factory-bean'引用
		if (!isConfigurationFrozen() || type == null || !allowEagerInit) {
			//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称,然后返回出去。
			return doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, allowEagerInit);
		}
		//定义一个Class-String[]的Map缓存，如果包含是否包含非单例，就引用单例和非单例Bean名称的映射Map；
		// 否则 引用仅依赖单例的bean名称的映射Map
		Map<Class<?>, String[]> cache =
				(includeNonSingletons ? this.allBeanNamesByType : this.singletonBeanNamesByType);
		//从缓存中获取type对应的BeanName数组
		String[] resolvedBeanNames = cache.get(type);
		//如果获取成功，就返回出去
		if (resolvedBeanNames != null) {
			return resolvedBeanNames;
		}
		//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称
		resolvedBeanNames = doGetBeanNamesForType(ResolvableType.forRawClass(type), includeNonSingletons, true);
		//获取工厂的类加载器，如果该类加载器加载过type
		if (ClassUtils.isCacheSafe(type, getBeanClassLoader())) {
			//将type与属于type的Bean类的所有Bean名称的映射关系添加到cache中
			cache.put(type, resolvedBeanNames);
		}
		//返回属于type的Bean类的所有Bean名称
		return resolvedBeanNames;
	}

	/**
	 * 获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称
	 * <ol>
	 *   <li>定义一个用于存储的匹配的bean名的ArrayList集合 【变量 result】</li>
	 *   <li><b>先从工厂收集到的所有BeanDefinition中查找：</b>
	 *    <ol>
	 *      <li>遍历工厂的BeanDefinition缓存集合【beanDefinitionNames】,元素为 beanName：
	 *       <ol>
	 *         <li>如果beanName不是别名:
	 *           <ol>
	 *             <li>获取beanName对应的合并后BootDefinition对象 【变量 mbd】</li>
	 *             <li>【这里的判断很多，主要就是判断mbd是否完整】如果mbd不是抽象类 且 (允许早期初始化 或者 mbd有指定bean类 或者  mbd没有设置延迟初始化
	 *             或者工厂配置了允许急切加载Bean类，即使对于标记为'lazy-init'的bean定义
	 *             也是如此【allowEagerClassLoading】) 且 mdb配置的FactoryBean名不需要急切初始化以确定其类型 ：
	 * 				 <ol>
	 * 				   <li>定义一个是否是FactoryBean类型标记【变量 isFactoryBean】：判断beanName和mbd所指的bean是否已定义为FactoryBean</li>
	 * 				   <li>获取mbd的BeanDefinitionHolder对象 【变量 dbd】</li>
	 * 				   <li>初始化匹配已找到标记为未找到【false,变量matchFound】</li>
	 * 				   <li>定义允许FactoryBean初始化标记【变量 allowFactoryBeanInit】：只要允许饿汉式初始化 或者 beanName已经在单例对象的高速缓存Map集合
	 * 				   【singletonObjects】有所属对象</li>
	 * 				   <li>定义是非延时装饰标记【变量 isNonLazyDecorated】：dbd获取成功 且 mbd没有设置延时初始化</li>
	 * 				   <li>如果不是FactoryBean类型，且(包含非单例 或者 beanName, mbd, dbd所指向的bean是单例),就将beanName的Bean类型是
	 * 				   否与type匹配的结果赋值给matchFound</li>
	 * 				   <li>如果是FactoryBean类型:
	 * 				    <ol>
	 * 				      <li>如果包含非单例 或者 mdb没有配置延时 或者 (允许FactoryBean初始化 且  beanName, mbd, dbd所指向的bean是单例),
	 * 				      就将beanName的Bean类型是否与type匹配的结果赋值给matchFound</li>
	 * 				      <li>如果不匹配， 将beanName改成解引用名，再来一次匹配：将beanName的Bean类型是否与type匹配的结果赋值给matchFound
	 * 				      (此时的beanName是解引用名)</li>
	 * 				    </ol>
	 * 				   </li>
	 * 				   <li>如果匹配成功,将beanName添加到result中</li>
	 * 				 </ol>
	 *             </li>
	 *             <li>捕捉BeanFactory无法加载给定bean的指定类时引发的异常 和 当BeanFactory遇到无效的bean定义时引发的异常:
	 *              <ol>
	 *                <li>如果是允许马上初始化，重新抛出异常</li>
	 *                <li>构建日志消息对象，并打印跟踪日志：如果ex是CannotLoadBeanClassException，描述忽略Bean 'beanName'的Bean类加载失败；
	 *                否则描述忽略bean定义'beanName'中无法解析的元数据</li>
	 *                <li>将要注册的异常对象添加到 抑制异常列表【DefaultSingletonBeanRegistry#suppressedExceptions】中</li>
	 *              </ol>
	 *             </li>
	 *           </ol>
	 *         </li>
	 *       </ol>
	 *      </li>
	 *    </ol>
	 *   </li>
	 *   <li><b>从工厂收集到的手动注册的单例对象中查找：</b>
	 *    <ol>
	 *      <li>遍历 手动注册单例的名称列表【manualSingletonNames】：
	 *       <ol>
	 *         <li>如果beanName所指的对象属于FactoryBean实例：
	 *          <ol>
	 *           <li>如果(包含非单例 或者 beanName所指的对象是单例) 且 beanName对应的Bean类型与type匹配,就将beanName
	 *           添加到result中，然后 continue</li>
	 *           <li>否则，将beanName改成FactoryBean解引用名</li>
	 *          </ol>
	 *         </li>
	 *         <li>如果beanName对应的Bean类型与type匹配（此时的beanName有可能是FactoryBeany解引用名），就将beanName添加
	 *         到result中</li>
	 *         <li>捕捉没有此类bean定义异常,打印跟踪消息：无法检查名称为'beanName'的手动注册的单例</li>
	 *       </ol>
	 *      </li>
	 *    </ol>
	 *   </li>
	 *   <li>将result转换成Stirng数组返回出去</li>
	 * </ol>
	 *
	 * @param type                 要匹配的通用类型的类或接口
	 * @param includeNonSingletons 是否包含非单例
	 * @param allowEagerInit       是否初始化FactoryBean和'factory-bean'引用
	 */
	private String[] doGetBeanNamesForType(ResolvableType type, boolean includeNonSingletons, boolean allowEagerInit) {
		//定义一个用于存储的匹配的bean名的ArrayList集合
		List<String> result = new ArrayList<>();

		// Check all bean definitions. 检查所有bean定义
		//遍历bean定义
		for (String beanName : this.beanDefinitionNames) {
			// Only consider bean as eligible if the bean name
			// is not defined as alias for some other bean.
			// 译：如果未将bean名称定义为其他bean的别名，则仅将bean视为可选。
			//如果beanName不是别名
			if (!isAlias(beanName)) {
				try {
					//获取beanName对应的合并后BootDefinition对象
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// Only check bean definition if it is complete.
					// 仅检查Bean定义是否完整
					// 如果mbd不是抽象
					//  	且 (允许饿汉式初始化
					//  		或者 mbd指定了bean类 或者 mbd没有设置延迟初始化
					//			或者 允许急切加载Bean类，即使对于标记为'lazy-init'的bean定义)
					//		且 mdb配置的FactoryBean名不需要急切初始化以确定其类型
					if (!mbd.isAbstract() && (allowEagerInit ||
							(mbd.hasBeanClass() || !mbd.isLazyInit() || isAllowEagerClassLoading()) &&
									!requiresEagerInitForType(mbd.getFactoryBeanName()))) {
						//是否是FactoryBean类型标记：判断beanName和mbd所指的bean是否已定义为FactoryBean
						boolean isFactoryBean = isFactoryBean(beanName, mbd);
						//获取mbd修饰的目标定义，
						// BeanDefinitionHolder:具有名称和别名的BeanDefinition的持有人
						BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
						//初始化匹配已找到标记为未找到
						boolean matchFound = false;
						//允许FactoryBean初始化标记：只要参数允许饿汉式初始化 或者 beanName已经在单例对象的高速缓存Map集合有所属对象
						boolean allowFactoryBeanInit = allowEagerInit || containsSingleton(beanName);
						//是非延时装饰标记：mdb有配置BeanDefinitionHolder 且 mbd没有设置延时初始化
						boolean isNonLazyDecorated = dbd != null && !mbd.isLazyInit();
						//如果不是FactoryBean类型
						if (!isFactoryBean) {
							//如果包含非单例 或者 beanName, mbd, dbd所指向的bean是单例
							if (includeNonSingletons || isSingleton(beanName, mbd, dbd)) {
								//将beanName的Bean类型是否与type匹配的结果赋值给matchFound
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						//如果是FactoryBean类型
						else {
							//如果包含非单例 或者 mdb没有配置延时 或者 (允许FactoryBean初始化 且  beanName, mbd, dbd所指向的bean是单例)
							if (includeNonSingletons || isNonLazyDecorated ||
									(allowFactoryBeanInit && isSingleton(beanName, mbd, dbd))) {
								//将beanName的Bean类型是否与type匹配的结果赋值给matchFound
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
							//如果不匹配
							if (!matchFound) {
								// In case of FactoryBean, try to match FactoryBean instance itself next.
								// 如果是FactoryBean，请尝试接下来匹配FactoryBean实例本身
								// 将beanName改成解引用名
								beanName = FACTORY_BEAN_PREFIX + beanName;
								//将beanName的Bean类型是否与type匹配的结果赋值给matchFound(此时的beanName是解引用名)
								matchFound = isTypeMatch(beanName, type, allowFactoryBeanInit);
							}
						}
						//如果匹配成功
						if (matchFound) {
							//将beanName添加到result中
							result.add(beanName);
						}
					}
				}
				//捕捉BeanFactory无法加载给定bean的指定类时引发的异常 和 当BeanFactory遇到无效的bean定义时引发的异常
				catch (CannotLoadBeanClassException | BeanDefinitionStoreException ex) {
					//如果是允许马上初始化，重新抛出异常
					if (allowEagerInit) {
						throw ex;
					}
					// Probably a placeholder: let's ignore it for type matching purposes.
					// 可能是占位符：处于类型匹配的目的，我们将其忽略
					// 构建日志消息：如果ex是CannotLoadBeanClassException，描述忽略Bean 'beanName'的Bean类加载失败；否则
					// 描述忽略bean定义'beanName'中无法解析的元数据
					LogMessage message = (ex instanceof CannotLoadBeanClassException) ?
							LogMessage.format("Ignoring bean class loading failure for bean '%s'", beanName) :
							LogMessage.format("Ignoring unresolvable metadata in bean definition '%s'", beanName);
					logger.trace(message, ex);
					//将要注册的异常对象添加到 抑制异常列表【DefaultSingletonBeanRegistry#suppressedExceptions】中
					onSuppressedException(ex);
				}
			}
		}


		// Check manually registered singletons too.
		// 也检查手动注册的单例
		// 遍历 手动注册单例的名称列表
		for (String beanName : this.manualSingletonNames) {
			try {
				// In case of FactoryBean, match object created by FactoryBean.
				// 对于FactoryBean，请匹配FactoryBean创建的对象
				// 如果beanName所指的对象属于FactoryBean实例
				if (isFactoryBean(beanName)) {
					//如果(包含非单例 或者 beanName所指的对象是单例) 且 beanName对应的Bean类型与type匹配
					if ((includeNonSingletons || isSingleton(beanName)) && isTypeMatch(beanName, type)) {
						//将beanName添加到result中
						result.add(beanName);
						// match found for this bean: do not match factorybean itself anymore.
						// 为此bean找到匹配项：不再匹配FactoryBean本身
						continue;
					}
					// In case of FactoryBean, try to match FactoryBean itself next.
					// 如果是FactoryBean,请尝试接下来匹配FactoryBean本身
					beanName = FACTORY_BEAN_PREFIX + beanName;
				}
				// Match raw bean instance (might be raw FactoryBean).
				//匹配原始bean实例（可能是原始FactoryBean)
				//如果beanName对应的Bean类型与type匹配（此时的beanName有可能是FactoryBeany解引用名）
				if (isTypeMatch(beanName, type)) {
					//将beanName添加到result中
					result.add(beanName);
				}
			}
			//捕捉没有此类bean定义异常
			catch (NoSuchBeanDefinitionException ex) {
				// Shouldn't happen - probably a result of circular reference resolution...
				// 不应该发生-可能是循环引用决议结果
				//打印跟踪消息：无法检查名称为'beanName'的手动注册的单例
				logger.trace(LogMessage.format("Failed to check manually registered singleton with name '%s'", beanName), ex);
			}
		}
		//将result转换成Stirng数组返回出去
		return StringUtils.toStringArray(result);
	}


	/**
	 * <p>根据给定参数尽可能的判断所指bean是否为单例，判断依据如下：
	 * 	<ol>
	 * 	    <li>如果存在dbd【不为 null 】:就判断mbd是否单例并返回其结果</li>
	 * 	    <li>否则判断beanName是否单例</li>
	 * 	</ol>
	 * </p>
	 *
	 * @param beanName bean名
	 * @param mbd      beanName对应的RootBeanDefinition，有可能是合并的RootBeanDefinition
	 * @param dbd      mbd修饰的目标定义
	 * @return
	 */
	private boolean isSingleton(String beanName, RootBeanDefinition mbd, @Nullable BeanDefinitionHolder dbd) {
		//如果存在mbd修饰的目标定义，就判断beanName对应的RootBeanDefinition是否单例，是就返回true，否则返回false；
		// 否则判断beanName是否单例，是就返回true；否则返回false
		return (dbd != null ? mbd.isSingleton() : isSingleton(beanName));
	}

	/**
	 * Check whether the specified bean would need to be eagerly initialized
	 * in order to determine its type.
	 * <p>检查是否需要急切初始化指定的bean以确定其类型,满足以下条件则认为需要急切初始化指定的bean以确定其类型<br/>
	 * 		<ol>
	 * 		 	<li>factoryBeanName确实是对应FactoryBean对象</li>
	 * 		 	<li>该工厂的注册器没有该factoryBean对象。</li>
	 * 		</ol>
	 * </p>
	 *
	 * @param factoryBeanName a factory-bean reference that the bean definition
	 *                        defines a factory method for
	 *                        -- 一个工厂bean引用，该BeanDefinition定义了一个工厂方法
	 * @return whether eager initialization is necessary
	 * -- 是否急切的初始化时必要的
	 */
	private boolean requiresEagerInitForType(@Nullable String factoryBeanName) {
		//fartoryBean引用名不为null 且 factoryBean引用名确实FactoryBean 且 该工厂的注册器注册器不包含factoryBean的单例实例 时
		// 返回true，否则返回false
		return (factoryBeanName != null && isFactoryBean(factoryBeanName) && !containsSingleton(factoryBeanName));
	}

	/**
	 * 返回与给定类型（包括子类）匹配的bean名称，根据bean定义或getObjectType的 值判断(如果是FactoryBenas)
	 *
	 * @param type the class or interface to match, or {@code null} for all concrete beans
	 *             --要匹配的类或接口，或者对于所有具体的bean,{@code null}
	 * @param <T>  要匹配的类型泛型
	 * @return 一个具有匹配bean的Map，其中包含bena名称作为键名，并包含对于bean实例作为值。
	 * @throws BeansException 如果无法创建一个bean
	 * @see #getBeansOfType(Class, boolean, boolean)
	 */
	@Override
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type) throws BeansException {
		return getBeansOfType(type, true, true);
	}

	/**
	 * 返回与给定类型（包括子类）匹配的bean名称，根据bean定义或getObjectType的 值判断(如果是FactoryBeans):
	 * <ol>
	 *  <li>获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的所有名称【变量 beanNames】</li>
	 *  <li>定义一个用于保存beanName和beanName所对应的实例对象的Map,长度为beanNames数组长度【变量result】</li>
	 *  <li>遍历类型匹配的beanNames,元素为beanName:
	 *   <ol>
	 *     <li>获取beanNamed的实例对象【变量 beanInstance】</li>
	 *     <li>如果实例对象不是NullBean,就将beanInstance与beanName绑定到result中</li>
	 *     <li>捕捉bean创建异常【{@link BeanCreationException}】【变量 ex】:
	 *      <ol>
	 *       <li>获取ex的具体异常【变量 rootCause】</li>
	 *       <li>如果具体异常 是 在引用当前正在创建的bean时引发异常【{@link BeanCurrentlyInCreationException}】:
	 *        <ol>
	 *         <li>将rootCause强转为BeanCreationException</li>
	 *         <li>获取引发改异常的bean名【变量 exBeanName】</li>
	 *         <li>如果exBeanName不为null 且 exBeanName正在创建:
	 *          <ol>
	 *           <li>如果日志级别是跟踪级别,打印跟踪日志：忽略与当前创建的bean的匹配 'exBeanName'</li>
	 *           <li>将要注册的异常对象添加到 抑制异常列表中</li>
	 *           <li>跳过本次循环</li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li>重新抛出异常ex</li>
	 *      </ol>
	 *     </li>
	 *   </ol>
	 *  </li>
	 *  <li>返回result</li>
	 * </ol>
	 *
	 * @param type the class or interface to match, or {@code null} for all concrete beans
	 *             --要匹配的类或接口，或者对于所有具体的bean,{@code null}
	 * @param <T>  要匹配的类型泛型
	 * @return 一个具有匹配bean的Map，其中包含bena名称作为键名，并包含对于bean实例作为值。
	 * @throws BeansException 如果无法创建一个bean
	 * @see #getBeansOfType(Class, boolean, boolean)
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> Map<String, T> getBeansOfType(@Nullable Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {
		//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的名称
		String[] beanNames = getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		//定义一个用于保存beanName和beanName所对应的实例对象的Map,长度为beanNames数组长度
		Map<String, T> result = new LinkedHashMap<>(beanNames.length);
		//遍历类型匹配的beanName
		for (String beanName : beanNames) {
			try {
				//获取beanNamed的实例对象
				Object beanInstance = getBean(beanName);
				//如果实例对象不是NullBean
				if (!(beanInstance instanceof NullBean)) {
					//将实例对象与beanName绑定到result中
					result.put(beanName, (T) beanInstance);
				}
			}
			//捕捉bean创建异常
			catch (BeanCreationException ex) {
				//获取具体异常
				Throwable rootCause = ex.getMostSpecificCause();
				//BeanCurrentlyInCreationException：在引用当前正在创建的bean时引发异常。通常在构造函数自动装配与
				// 当前构造的bean匹配时发生。
				//如果具体异常 是 在引用当前正在创建的bean时引发异常
				if (rootCause instanceof BeanCurrentlyInCreationException) {
					//将具体异常强转为BeanCreationException
					BeanCreationException bce = (BeanCreationException) rootCause;
					//获取引发改异常的bean名
					String exBeanName = bce.getBeanName();
					//如果引发异常的bean名不为null 且 exBeanName正在创建
					if (exBeanName != null && isCurrentlyInCreation(exBeanName)) {
						//如果日志级别是跟踪级别
						if (logger.isTraceEnabled()) {
							//打印跟踪日志：忽略与当前创建的bean的匹配 'exBeanName'
							logger.trace("Ignoring match to currently created bean '" + exBeanName + "': " +
									ex.getMessage());
						}
						//将要注册的异常对象添加到 抑制异常列表 中
						onSuppressedException(ex);
						// Ignore: indicates a circular reference when autowiring constructors.
						// We want to find matches other than the currently created bean itself.
						// 忽略：自动装配构造函数时出现循环引用。我们要查找当前创建的bean本身以外的匹配项。
						//跳过本次循环
						continue;
					}
				}
				//重新抛出异常
				throw ex;
			}
		}
		//返回用于保存beanName和beanName所对应的实例对象的Map
		return result;
	}


	@Override
	public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
		List<String> result = new ArrayList<>();
		for (String beanName : this.beanDefinitionNames) {
			BeanDefinition bd = this.beanDefinitionMap.get(beanName);
			if (bd != null && !bd.isAbstract() && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		for (String beanName : this.manualSingletonNames) {
			if (!result.contains(beanName) && findAnnotationOnBean(beanName, annotationType) != null) {
				result.add(beanName);
			}
		}
		return StringUtils.toStringArray(result);
	}

	@Override
	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
		String[] beanNames = getBeanNamesForAnnotation(annotationType);
		Map<String, Object> result = CollectionUtils.newLinkedHashMap(beanNames.length);
		for (String beanName : beanNames) {
			Object beanInstance = getBean(beanName);
			if (!(beanInstance instanceof NullBean)) {
				result.put(beanName, beanInstance);
			}
		}
		return result;
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException {

		return findAnnotationOnBean(beanName, annotationType, true);
	}

	@Override
	@Nullable
	public <A extends Annotation> A findAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		return findMergedAnnotationOnBean(beanName, annotationType, allowFactoryBeanInit)
				.synthesize(MergedAnnotation::isPresent).orElse(null);
	}

	private <A extends Annotation> MergedAnnotation<A> findMergedAnnotationOnBean(
			String beanName, Class<A> annotationType, boolean allowFactoryBeanInit) {

		Class<?> beanType = getType(beanName, allowFactoryBeanInit);
		if (beanType != null) {
			MergedAnnotation<A> annotation =
					MergedAnnotations.from(beanType, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		if (containsBeanDefinition(beanName)) {
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// Check raw bean class, e.g. in case of a proxy.
			if (bd.hasBeanClass() && bd.getFactoryMethodName() == null) {
				Class<?> beanClass = bd.getBeanClass();
				if (beanClass != beanType) {
					MergedAnnotation<A> annotation =
							MergedAnnotations.from(beanClass, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
					if (annotation.isPresent()) {
						return annotation;
					}
				}
			}
			// Check annotations declared on factory method, if any.
			Method factoryMethod = bd.getResolvedFactoryMethod();
			if (factoryMethod != null) {
				MergedAnnotation<A> annotation =
						MergedAnnotations.from(factoryMethod, SearchStrategy.TYPE_HIERARCHY).get(annotationType);
				if (annotation.isPresent()) {
					return annotation;
				}
			}
		}
		return MergedAnnotation.missing();
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableListableBeanFactory interface
	//---------------------------------------------------------------------

	/**
	 * 用相应的自动装配值注册一个特殊的依赖类型：
	 * <ol>
	 *  <li>如果dependencyType为null，抛出异常</li>
	 *  <li>如果相应的自动注入值不为null：
	 *   <ol>
	 *    <li>如果autowiredValue不是ObjectFactory的实例或者autowiredValue是dependencyType的实例，
	 *    就抛出非法参数异常</li>
	 *    <li>绑定dependencyType和autowiredValue到 从依赖项类型映射到相应的自动装配值的缓存
	 *    【{@link #resolvableDependencies}】中</li>
	 *   </ol>
	 *  </li>
	 * </ol>
	 *
	 * @param dependencyType the dependency type to register. This will typically
	 *                       be a base interface such as BeanFactory, with extensions of it resolved
	 *                       as well if declared as an autowiring dependency (e.g. ListableBeanFactory),
	 *                       as long as the given value actually implements the extended interface.
	 *                       -- 要注册的依赖项类型。这通常
	 *                       可以是BeanFactory之类的基本接口，并且只要声明为自动装配依赖项（例如ListableBeanFactory），
	 *                       它的扩展名也可以解析，只要给定值实际实现扩展接口即可
	 * @param autowiredValue the corresponding autowired value. This may also be an
	 *                       implementation of the {@link org.springframework.beans.factory.ObjectFactory}
	 *                       interface, which allows for lazy resolution of the actual target value.
	 *                       -- 相应的自动注入值。这也可以是{@link org.springframework.beans.factory.ObjectFactory}接口的实现，
	 */
	@Override
	public void registerResolvableDependency(Class<?> dependencyType, @Nullable Object autowiredValue) {
		//如果依赖类型为null，抛出异常
		Assert.notNull(dependencyType, "Dependency type must not be null");
		//如果相应的自动注入值不为null
		if (autowiredValue != null) {
			//如果  相应的自动注入值 不是 ObjectFactory的实例 或者 autowiredValue是要注册的依赖项类型的实例
			if (!(autowiredValue instanceof ObjectFactory || dependencyType.isInstance(autowiredValue))) {
				//抛出非法参数异常：值 [autowiredValue] 不是实现指定依赖类型 [dependencyType]
				throw new IllegalArgumentException("Value [" + autowiredValue +
						"] does not implement specified dependency type [" + dependencyType.getName() + "]");
			}
			//绑定要注册的依赖项类型 和 相应自动注入值的关系
			this.resolvableDependencies.put(dependencyType, autowiredValue);
		}
	}

	/**
	 * 确定指定的bean定义是否可以自动注入:
	 * <ol>
	 *     <li>获取此BeanFactory的自动装配候选解析器</li>
	 *     <li>交由{@link #isAutowireCandidate(String, DependencyDescriptor, AutowireCandidateResolver)}
	 *     处理并将结果返回出去</li>
	 * </ol>
	 *
	 * @param beanName   the name of the bean to check -- 要检查的bean名
	 * @param descriptor the descriptor of the dependency to resolve -- 要解决的依赖项的描述符
	 * @return 是否应将bean视为自动装配候选对象
	 * @throws NoSuchBeanDefinitionException 如果没有给定名称的bean
	 */
	@Override
	public boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException {
		return isAutowireCandidate(beanName, descriptor, getAutowireCandidateResolver());
	}

	/**
	 * <p>确定指定的bean定义是否可以自动注入:
	 *  <ol>
	 *   <li><b>解决beanName存在于该工厂的情况:</b>
	 *    <ol>
	 *     <li>去除开头的'&'字符，返回剩余的字符串作为转换后的Bean名称【全类名】【变量 beanDefinitionName】</li>
	 *     <li>如果工厂包含beanDefinitionName得BeanDefinition对象,就确定beanDefinitionName的合并后RootBeanDefinition
	 *      是否符合自动装配候选条件，以注入到声明匹配类型依赖项的其他bean中并将结果返回出去</li>
	 *     <li>如果beanName在该BeanFactory的单例对象的高速缓存Map集合中,
	 *     获取beanName的Class对象，从而构建出RootBeanDefinition实例对象，最后确定该RootBeanDefinition实例对象是否符合自动装
	 *      配候选条件，以注入到声明匹配类型依赖项的其他bean中并将结果返回出去</li>
	 *    </ol>
	 *   </li>
	 *   <li><b>解决beanName存在于该工厂的父工厂的情况：</b>
	 *    <ol>
	 *     <li>获取父工厂【变量 parent】</li>
	 *     <li>如果父工厂是DefaultListableBeanFactory的实例对象,递归交由父工厂的该方法处理并将结果返回</li>
	 *     <li>如果父工厂是ConfigurableListableBeanFactory的实例对象,就递归交由父工厂的isAutowireCandidate(String, DependencyDescriptor)
	 *     进行处理并将结果返回出去</li>
	 *    </ol>
	 *   </li>
	 *   <li>如果上面各种情况都没有涉及，默认是返回true，表示应将bean视为自动装配候选对象</li>
	 *  </ol>
	 * </p>
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * <p>确定指定的bean定义是否符合自动装配候选条件，以注入到声明匹配类型依赖项的其他bean中。</p>
	 *
	 * @param beanName   the name of the bean definition to check
	 *                   -- 要检查的beanDefinition名称
	 * @param descriptor the descriptor of the dependency to resolve
	 *                   -- 要解析的依赖项的描述符
	 * @param resolver   the AutowireCandidateResolver to use for the actual resolution algorithm
	 *                   -- AutowireCandidateResolver用于实际的解析算法
	 * @return whether the bean should be considered as autowire candidate
	 * -- 是否应将bean视为自动装配候选对象
	 * @see #isAutowireCandidate(String, RootBeanDefinition, DependencyDescriptor, AutowireCandidateResolver)
	 */
	protected boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor, AutowireCandidateResolver resolver)
			throws NoSuchBeanDefinitionException {
		//去除开头的'&'字符，返回剩余的字符串作为转换后的Bean名称【全类名】
		String beanDefinitionName = BeanFactoryUtils.transformedBeanName(beanName);
		//如果工厂包含beanDefinitionName得BeanDefinition对象
		if (containsBeanDefinition(beanDefinitionName)) {
			//确定beanDefinitionName的合并后RootBeanDefinition是否符合自动装配候选条件，以注入到声明匹配类型依赖项的其他bean中
			// 并将结果返回出去
			return isAutowireCandidate(beanName, getMergedLocalBeanDefinition(beanDefinitionName), descriptor, resolver);
		}
		//这里应该是直接调用registerSingeton(String,Object)方法的情况
		//如果beanName在该BeanFactory的singletonObjects【单例对象的高速缓存Map集合】中
		else if (containsSingleton(beanName)) {
			//获取beanName的Class对象，从而构建出RootBeanDefinition实例对象，最后确定该RootBeanDefinition实例对象是否符合自动装
			// 配候选条件，以注入到声明匹配类型依赖项的其他bean中并将结果返回出去
			return isAutowireCandidate(beanName, new RootBeanDefinition(getType(beanName)), descriptor, resolver);
		}
		//获取父工厂
		BeanFactory parent = getParentBeanFactory();
		//如果父工厂是DefaultListableBeanFactory的实例对象
		if (parent instanceof DefaultListableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			// 在该工厂中找不到BeanDefinition ->委托给父对象
			// 递归交由父工厂处理并将结果返回
			return ((DefaultListableBeanFactory) parent).isAutowireCandidate(beanName, descriptor, resolver);
		}
		//如果父工厂是ConfigurableListableBeanFactory的实例对象
		else if (parent instanceof ConfigurableListableBeanFactory) {
			// If no DefaultListableBeanFactory, can't pass the resolver along.
			// 如果没有DefaultLisableBeanFactory,则无法传递解析器
			// 这个时候，由于ConfigurableListableBeanFactory没有提供isAutowireCandidate(String, DependencyDescriptor, AutowireCandidateResolver)的
			// 重载方法，所以递归交由父工厂的isAutowireCandidate(String, DependencyDescriptor)进行处理并将结果返回出去
			return ((ConfigurableListableBeanFactory) parent).isAutowireCandidate(beanName, descriptor);
		} else {
			//如果上面各种情况都没有涉及，默认是返回true，表示应将bean视为自动装配候选对象
			return true;
		}
	}

	/**
	 * <p>
	 * 确定mbd可以自动注入到descriptor所包装的field/methodParam中
	 *  <ol>
	 *   <li>去除开头的'&'字符，返回剩余的字符串作为转换后的Bean名称【可能是全类名】【变量 beanDefinitionName】</li>
	 *   <li>为mdb解析出对应的bean class对象</li>
	 *   <li>如果mbd指明了引用非重载方法的工厂方法名称【{@link RootBeanDefinition#isFactoryMethodUnique}】 且 mbd还没有缓存
	 *   用于自省的唯一工厂方法候选【{@link RootBeanDefinition#factoryMethodToIntrospect}】:如果可能，新建一个ConstuctorResolver
	 *   对象来解析mbd中factory方法</li>
	 *   <li>根据mbd,beanName,beanDefinitionName的别名构建一个BeanDefinitionHolder实例,交给resolver进行判断,
	 *   是否可以自动注入并将结果返回出去。resolver的判断依据还是看mdb的{@link AbstractBeanDefinition#isAutowireCandidate()}结果。</li>
	 *  </ol>
	 * </p>
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * <p>确定指定的bean定义是否符合自动装配候选条件，以注入到声明匹配类型依赖项的其他bean中。</p>
	 *
	 * @param beanName   the name of the bean definition to check -- 要检查的beanDefinition名
	 * @param mbd        the merged bean definition to check -- 要检查的合并后BeanDefinition
	 * @param descriptor the descriptor of the dependency to resolve -- 要解析的依赖项的描述符
	 * @param resolver   the AutowireCandidateResolver to use for the actual resolution algorithm
	 *                   -- AutowireCandidateResolver用于实际的分析算法
	 * @return whether the bean should be considered as autowire candidate
	 * -- 是否应将bean视为自动装配候选对象
	 */
	protected boolean isAutowireCandidate(String beanName, RootBeanDefinition mbd,
										  DependencyDescriptor descriptor, AutowireCandidateResolver resolver) {
		//去除开头的'&'字符，返回剩余的字符串作为转换后的Bean名称【可能是全类名】
		String beanDefinitionName = BeanFactoryUtils.transformedBeanName(beanName);
		//为mdb解析出对应的bean class对象
		resolveBeanClass(mbd, beanDefinitionName);
		//如果mbd指明了引用非重载方法的工厂方法名称 且 mbd还没有缓存用于自省的唯一工厂方法候选
		if (mbd.isFactoryMethodUnique && mbd.factoryMethodToIntrospect == null) {
			//如果可能，新建一个ConstuctorResolver对象来解析mbd中factory方法
			new ConstructorResolver(this).resolveFactoryMethodIfPossible(mbd);
		}
		//根据mbd,beanName,beanDefinitionName的别名构建一个BeanDefinitionHolder实例,交给resolver进行判断
		// 是否可以自动注入。resolver的判断依据还是看mdb的isAutowireCandidate()结果。
		return resolver.isAutowireCandidate(
				new BeanDefinitionHolder(mbd, beanName, getAliases(beanDefinitionName)), descriptor);
	}


	/**
	 * 获取该工厂beanName的BeanDefinition对象：
	 * <ol>
	 *     <li>从该工厂的BeanDefinitionMap缓存【beanDefinitionMap】中获取beanName
	 *     的BeanDefinition对象【忽略父级工厂】</li>
	 *     <li>获取不到时，打印日志并抛出NoSuchBeanDefinitionException</li>
	 * </ol>
	 *
	 * @param beanName name of the bean to find a definition for -- 查找定义的bean名称
	 */
	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		//从 Bean定义对象的映射 中获取beanName对应的BeanDefinition对象
		BeanDefinition bd = this.beanDefinitionMap.get(beanName);
		//如果从 Bean定义对象的映射 没有找到
		if (bd == null) {
			//如果当前日志级别为跟踪
			if (logger.isTraceEnabled()) {
				//打印日志 ： 在DefaultListBeanFactory中没有找到名为 beanName 的bean
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			//抛出没有此类bean异常
			throw new NoSuchBeanDefinitionException(beanName);
		}
		//返回beanName对应的BeanDefinition对象
		return bd;
	}

	@Override
	public Iterator<String> getBeanNamesIterator() {
		CompositeIterator<String> iterator = new CompositeIterator<>();
		iterator.add(this.beanDefinitionNames.iterator());
		iterator.add(this.manualSingletonNames.iterator());
		return iterator;
	}

	@Override
	protected void clearMergedBeanDefinition(String beanName) {
		super.clearMergedBeanDefinition(beanName);
		this.mergedBeanDefinitionHolders.remove(beanName);
	}

	@Override
	public void clearMetadataCache() {
		//清空合并的BeanDefinition缓存，删除尚未被认为符合完整元数据缓存条件的Bean条目
		super.clearMetadataCache();
		//删除有关按类型映射的任何映射:清空 单例和非单例Bean名称的映射按依赖项类型 进行键控的Map【allBeanNamesByType】以及
		// 仅依赖单例的bean名称的映射，按依赖项类型 进行键控的Map【singletonBeanNamesByType】
		clearByTypeCache();
	}

	@Override
	public void freezeConfiguration() {
		//表示冻结所有beanDefinition，也表示可以为所有bean缓存bean定义元数据，也表已注册bean定义将不会被进一步修改或后处理
		this.configurationFrozen = true;
		//缓存的BeanDefinition名称数组，以防配置被冻结
		this.frozenBeanDefinitionNames = StringUtils.toStringArray(this.beanDefinitionNames);
	}


	@Override
	public boolean isConfigurationFrozen() {
		return this.configurationFrozen;
	}

	/**
	 * Considers all beans as eligible for metadata caching
	 * if the factory's configuration has been marked as frozen.
	 * <p>如果工厂的配置被标记为冻结，则认为所有Bean都符合元数据缓存的条件</p>
	 *
	 * @see #freezeConfiguration()
	 */
	@Override
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		//如果可以为所有bean缓存bean定义元数据 || beanName有资格缓存其BeanDefinition元数据 就返回 true；否则返回false
		return (this.configurationFrozen || super.isBeanEligibleForMetadataCaching(beanName));
	}


	/**
	 * 实例化所有剩余的（非延迟加载的）单例bean，包括FactoryBean
	 * @throws BeansException
	 */
	@Override
	public void preInstantiateSingletons() throws BeansException {
		if (logger.isTraceEnabled()) {
			logger.trace("Pre-instantiating singletons in " + this);
		}

		// Iterate over a copy to allow for init methods which in turn register new bean definitions.
		// While this may not be part of the regular factory bootstrap, it does otherwise work fine.
		// 将所有的beanNames 保存到集合中
		List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);

		// Trigger initialization of all non-lazy singleton beans...
		// 触发所有的非懒加载的 singleton beans 的初始化操作
		for (String beanName : beanNames) {
			//合并父 Bean 中的配置，注意 <bean id="" class="" parent="" /> 中的 parent
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			// 非抽象、非懒加载的 singletons。如果配置了 'abstract = true'，那是不需要初始化的
			if (!bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
				// 处理 FactoryBean
				if (isFactoryBean(beanName)) {
					// FactoryBean 的话，在 beanName 前面加上 ‘&’ 符号。再调用 getBean
					Object bean = getBean(FACTORY_BEAN_PREFIX + beanName);
					// 判断当前 FactoryBean 是否是 SmartFactoryBean 的实现，此处忽略，直接跳过
					if (bean instanceof SmartFactoryBean<?> smartFactoryBean && smartFactoryBean.isEagerInit()) {
						getBean(beanName);
					}
				} else {
					// 对于普通的 Bean，只要调用 getBean(beanName) 方法就进行初始化
					getBean(beanName);
				}
			}
		}

		// Trigger post-initialization callback for all applicable beans...
		// 遍历beanDefinitionNames集合，触发所有SmartInitializingSingleton类型的bean实例的afterSingletonsInstantiated方法回调
		for (String beanName : beanNames) {
			//获取单例beanName对应的实例singletonInstance
			Object singletonInstance = getSingleton(beanName);
			if (singletonInstance instanceof SmartInitializingSingleton smartSingleton) {
				StartupStep smartInitialize = this.getApplicationStartup().start("spring.beans.smart-initialize")
						.tag("beanName", beanName);
				//回调afterSingletonsInstantiated方法，即在bean实例化之后回调该方法
				//这是一个扩展点，对于所有非延迟初始化的SmartInitializingSingleton类型的单例bean初始化完毕之后会进行回调
				smartSingleton.afterSingletonsInstantiated();
				smartInitialize.end();
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanDefinitionRegistry interface
	//---------------------------------------------------------------------

	/**
	 * 在此注册表缓存中注册一个新的 bean 定义
	 *
	 * @param beanName       the name of the bean instance to register 要注册的 bean 实例的名称
	 * @param beanDefinition definition of the bean instance to register 要注册的 bean 实例的定义
	 * @throws BeanDefinitionStoreException
	 */
	@Override
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {

		Assert.hasText(beanName, "Bean name must not be empty");
		Assert.notNull(beanDefinition, "BeanDefinition must not be null");
		//bean definition注册前的校验，methodOverrides校验
		if (beanDefinition instanceof AbstractBeanDefinition abd) {
			try {
				abd.validate();
			} catch (BeanDefinitionValidationException ex) {
				throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
						"Validation of bean definition failed", ex);
			}
		}
		//尝试从注册表缓存中查找当前beanName的BeanDefinition
		BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
		/*
		 * 1 如果找到了同名的BeanDefinition，进行ban定义覆盖的校验和操作
		 */
		if (existingDefinition != null) {
			/*
			 * 判断是否不允许 bean 的覆盖
			 *
			 * allowBeanDefinitionOverriding属性我们在“customizeBeanFactory配置beanFactory”的部分已经讲过
			 * 普通应用默认为true，boot应用默认false，可以自定义配置
			 */
			if (!isAllowBeanDefinitionOverriding()) {
				//如果不允许，那么就是出现同名bean，那么直接抛出异常
				throw new BeanDefinitionOverrideException(beanName, beanDefinition, existingDefinition);
			}
			/*
			 * 否则，表示允许，继续判断角色相关，不必关心
			 * 打印日志，用框架定义的 Bean 覆盖用户自定义的 Bean
			 */
			else if (existingDefinition.getRole() < beanDefinition.getRole()) {
				// e.g. was ROLE_APPLICATION, now overriding with ROLE_SUPPORT or ROLE_INFRASTRUCTURE
				if (logger.isInfoEnabled()) {
					logger.info("Overriding user-defined bean definition for bean '" + beanName +
							"' with a framework-generated bean definition: replacing [" +
							existingDefinition + "] with [" + beanDefinition + "]");
				}
			}
			/*
			 * 否则，表示允许，继续如果当前的beanDefinition不等于找到的此前的existingDefinition
			 * 打印日志，将会使用新beanDefinition覆盖旧的beanDefinition
			 */
			else if (!beanDefinition.equals(existingDefinition)) {
				if (logger.isDebugEnabled()) {
					logger.debug("Overriding bean definition for bean '" + beanName +
							"' with a different definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			/*
			 * 否则，表示允许，打印日志，将会使用同样（equals比较返回true）新的beanDefinition覆盖旧的beanDefinition
			 */
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("Overriding bean definition for bean '" + beanName +
							"' with an equivalent definition: replacing [" + existingDefinition +
							"] with [" + beanDefinition + "]");
				}
			}
			//使用新的beanDefinition覆盖旧的existingDefinition
			this.beanDefinitionMap.put(beanName, beanDefinition);
		}
		/*2 如果没找到同名的BeanDefinition，这是正常情况*/
		else {
			/*如果已经有其他任何bean实例开始初始化了*/
			if (hasBeanCreationStarted()) {
				// Cannot modify startup-time collection elements anymore (for stable iteration)
				//加锁防止并发操作集合
				synchronized (this.beanDefinitionMap) {
					//当前的beanName和beanDefinition存入beanDefinitionMap缓存
					this.beanDefinitionMap.put(beanName, beanDefinition);
					//当前的beanName存入beanDefinitionNames缓存
					//重新生成一个list集合替换
					List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames.size() + 1);
					updatedDefinitions.addAll(this.beanDefinitionNames);
					updatedDefinitions.add(beanName);
					this.beanDefinitionNames = updatedDefinitions;
					//当前的beanName从手动注册bean名称集合manualSingletonNames缓存中移除
					//因为如果这里自动注册了beanName，那么需要从manualSingletonNames缓存中移除代表手动注册的单例beanName。
					removeManualSingletonName(beanName);
				}
			}
			/*否则，其他任何bean实例没有开始初始化*/
			else {
				//仍处于启动注册阶段，不加锁
				//当前的beanName和beanDefinition存入beanDefinitionMap缓存
				this.beanDefinitionMap.put(beanName, beanDefinition);
				//当前的beanName存入beanDefinitionNames缓存
				this.beanDefinitionNames.add(beanName);
				//当前的beanName从手动注册bean名称集合manualSingletonNames缓存中移除
				//因为如果这里自动注册了beanName，那么需要从manualSingletonNames缓存中移除代表手动注册的单例beanName。
				removeManualSingletonName(beanName);
			}
			//仅仅在与初始化时才会使用到，很少使用
			this.frozenBeanDefinitionNames = null;
		}
		/*
		 * 3 如果找到的旧的BeanDefinition不为null，或者单例bean实例的缓存singletonObjects已中包含给定beanName的实例
		 * 那么将当前beanName对应的在DefaultSingletonBeanRegistry中的实例缓存清除，需要重新生成实例
		 */
		if (existingDefinition != null || containsSingleton(beanName)) {
			/*
			 * 将当前beanName对应的在DefaultSingletonBeanRegistry中的实例缓存清除
			 * 重置给定 beanName 的所有 bean 定义缓存，包括从它派生的 bean 的缓存(merge)。
			 * 以及重置以给定beanName为父类bean的子类Bean缓存。
			 */
			resetBeanDefinition(beanName);
		}
		/*
		 * 4 否则，如果此工厂的 bean 定义是否冻结，即不应进一步修改或后处理。
		 * 那么删除所有的按类型映射的任何缓存：allBeanNamesByType和singletonBeanNamesByType
		 * 在finishBeanFactoryInitialization方法中就会冻结 bean 定义，并且进行bean初始化操作
		 * 一般不会出现
		 */
		else if (isConfigurationFrozen()) {
			clearByTypeCache();
		}
	}

	@Override
	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		Assert.hasText(beanName, "'beanName' must not be empty");

		BeanDefinition bd = this.beanDefinitionMap.remove(beanName);
		if (bd == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}

		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			synchronized (this.beanDefinitionMap) {
				List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames);
				updatedDefinitions.remove(beanName);
				this.beanDefinitionNames = updatedDefinitions;
			}
		} else {
			// Still in startup registration phase
			this.beanDefinitionNames.remove(beanName);
		}
		this.frozenBeanDefinitionNames = null;

		resetBeanDefinition(beanName);
	}

	/**
	 * Reset all bean definition caches for the given bean,
	 * including the caches of beans that are derived from it.
	 * <p>Called after an existing bean definition has been replaced or removed,
	 * triggering {@link #clearMergedBeanDefinition}, {@link #destroySingleton}
	 * and {@link MergedBeanDefinitionPostProcessor#resetBeanDefinition} on the
	 * given bean and on all bean definitions that have the given bean as parent.
	 * 重置给定 bean 的所有 bean 定义缓存，包括从它派生的 bean 的缓存。
	 *
	 * @param beanName the name of the bean to reset
	 * @see #registerBeanDefinition
	 * @see #removeBeanDefinition
	 */
	protected void resetBeanDefinition(String beanName) {
		// Remove the merged bean definition for the given bean, if already created.
		//删除给定beanName的mergedBeanDefinitions的缓存，这是已合并的bean定义缓存
		clearMergedBeanDefinition(beanName);

		// Remove corresponding bean from singleton cache, if any. Shouldn't usually
		// be necessary, rather just meant for overriding a context's default beans
		// (e.g. the default StaticMessageSource in a StaticApplicationContext).
		//从单例缓存中删除相应的单例（如果有），际上就是删除DefaultSingletonBeanRegistry中的关于单例bean实现的缓存
		destroySingleton(beanName);

		// Notify all post-processors that the specified bean definition has been reset.
		//通知所有后处理器已重置指定的 bean 定义。
		for (MergedBeanDefinitionPostProcessor processor : getBeanPostProcessorCache().mergedDefinition) {
			processor.resetBeanDefinition(beanName);
		}

		// Reset all bean definitions that have the given bean as parent (recursively).
		// 重置所有以当前beanName为父类bean的子类Bean
		for (String bdName : this.beanDefinitionNames) {
			if (!beanName.equals(bdName)) {
				BeanDefinition bd = this.beanDefinitionMap.get(bdName);
				// Ensure bd is non-null due to potential concurrent modification of beanDefinitionMap.
				//如果 bd 不为null，并且给定beanName等于bd的parentName属性
				if (bd != null && beanName.equals(bd.getParentName())) {
					//递归调用resetBeanDefinition重置BeanDefinition
					resetBeanDefinition(bdName);
				}
			}
		}
	}

	/**
	 * Only allows alias overriding if bean definition overriding is allowed.
	 */
	@Override
	protected boolean allowAliasOverriding() {
		return isAllowBeanDefinitionOverriding();
	}

	/**
	 * Also checks for an alias overriding a bean definition of the same name.
	 */
	@Override
	protected void checkForAliasCircle(String name, String alias) {
		super.checkForAliasCircle(name, alias);
		if (!isAllowBeanDefinitionOverriding() && containsBeanDefinition(alias)) {
			throw new IllegalStateException("Cannot register alias '" + alias +
					"' for name '" + name + "': Alias would override bean definition '" + alias + "'");
		}
	}

	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		//在给定的bean名称下，在bean注册器中将给定的现有对象注册为单例：
		super.registerSingleton(beanName, singletonObject);
		//更新工厂内部的手动单例名称集，更新触发条件为bean定义映射集合中不包含该beanName的映射，更新操作为
		// 添加beanName到manualSingletonNames中
		updateManualSingletonNames(set -> set.add(beanName), set -> !this.beanDefinitionMap.containsKey(beanName));
		//删除有关按类型映射的任何映射:清空 单例和非单例Bean名称的映射按依赖项类型 进行键控的Map【allBeanNamesByType】以及 仅依赖单例的bean名称的映射，按依赖项类型 进行键控的Map【singletonBeanNamesByType】
		clearByTypeCache();
	}

	/**
	 * 销毁当前beanFactory中所有缓存的单例bean，并且进行销毁回调（如果设置）
	 * 清除相关缓存
	 */
	@Override
	public void destroySingletons() {
		//调用父类的方法销毁单例bean，并且进行销毁回调（如果设置了）
		super.destroySingletons();
		//清空DefaultListableBeanFactory类自己的manualSingletonNames属性集合
		//即清空所有手动注册的bean，所谓手动注册，就是调用registerSingleton(String beanName, Object singletonObject)方法注册的bean
		updateManualSingletonNames(Set::clear, set -> !set.isEmpty());
		//删除有关按类型映射的任何缓存，即清空allBeanNamesByType和singletonBeanNamesByType集合
		clearByTypeCache();
	}

	/**
	 * 销毁指定beanName对应的Bean
	 */
	@Override
	public void destroySingleton(String beanName) {
		//调用父类的destroySingleton方法
		super.destroySingleton(beanName);
		//当前的beanName从手动注册bean名称集合manualSingletonNames缓存中移除
		removeManualSingletonName(beanName);
		//删除有关按类型映射的任何缓存，即清空allBeanNamesByType和singletonBeanNamesByType集合
		clearByTypeCache();
	}

	/**
	 * 当前的beanName从manualSingletonNames缓存中移除
	 * 实际上是调用updateManualSingletonNames方法，我们在前面讲的destroySingletons()方法中就是见识过这个方法
	 * updateManualSingletonNames方法方法用于更新manualSingletonNames集合的数据
	 *
	 * @param beanName 要移除的beanName
	 */
	private void removeManualSingletonName(String beanName) {
		//action操作，表示移除给定beanName，lambda表达式的应用
		//condition操作，判断集合是否包含给定beanName，lambda表达式的应用
		updateManualSingletonNames(set -> set.remove(beanName), set -> set.contains(beanName));
	}


	/**
	 * <p>更新工厂内部的手动单例名称集:
	 * 	<ol>
	 * 	    <li>如果该工厂已经开始创建Bean,会使用BeanDefinitionMap作为锁，以保证线程安全</li>
	 * 	    <li>如果manualSingletonNames【手动注册单例的名称列表】满足修改操作的前提条件【condition】,执行对
	 * 	    manualSingletonNames【手动注册单例的名称列表】的修改操作【action】</li>
	 * 	</ol>
	 * </p>
	 * Update the factory's internal set of manual singleton names.
	 * <p>更新工厂内部的手动单例名称集</p>
	 *
	 * @param action    the modification action -- 修改操作
	 * @param condition a precondition for the modification action
	 *                  (if this condition does not apply, the action can be skipped)
	 *                  -- 修改操作的前提条件(如果不适用此条件，则可以该操作)
	 */
	private void updateManualSingletonNames(Consumer<Set<String>> action, Predicate<Set<String>> condition) {
		//如果该工厂已经开始创建Bean
		if (hasBeanCreationStarted()) {
			// Cannot modify startup-time collection elements anymore (for stable iteration)
			// 无法在修改启动时收集元素(用于稳定的迭代)
			// 使用BeanDefinitionMap作为锁，保证线程安全
			synchronized (this.beanDefinitionMap) {
				//如果manualSingletonNames满足修改操作的前提条件
				if (condition.test(this.manualSingletonNames)) {
					//新建一个manualSigletonNames的副本，用于更新操作
					Set<String> updatedSingletons = new LinkedHashSet<>(this.manualSingletonNames);
					//执行对updatedSingletons的修改操作
					action.accept(updatedSingletons);
					//将结果覆盖真正的manualSingletonNames，制作
					this.manualSingletonNames = updatedSingletons;
				}
			}
		} else {//如果该工厂还没开始创建Bean
			// Still in startup registration phase 仍处于注册阶段
			//如果manualSingletonNames满足修改操作的前提条件
			if (condition.test(this.manualSingletonNames)) {
				//执行对manualSingletonNames的修改操作
				action.accept(this.manualSingletonNames);
			}
		}
	}

	/**
	 * <p>删除有关按类型映射的任何映射:清空 单例和非单例Bean名称的映射按依赖项类型
	 * 进行键控的Map【allBeanNamesByType】以及 仅依赖单例的bean名称的映射，按依赖项类型
	 * 进行键控的Map【singletonBeanNamesByType】</p>
	 * Remove any assumptions about by-type mappings.
	 * <p>删除有关按类型映射的任何映射</p>
	 */
	private void clearByTypeCache() {
		//清空 单例和非单例Bean名称的映射按依赖项类型进行键控的Map【allBeanNamesByType】
		this.allBeanNamesByType.clear();
		//情空 仅依赖单例的bean名称的映射，按依赖项类型进行键控的Map【singletonBeanNamesByType】
		this.singletonBeanNamesByType.clear();
	}


	//---------------------------------------------------------------------
	// Dependency resolution functionality
	//---------------------------------------------------------------------

	/**
	 * 解析与给定对象类型唯一匹配的bean实例，包括其bean名:
	 * <ol>
	 *  <li>如果requiredType为null,抛出异常</li>
	 *  <li>解析与requiredType唯一匹配的bean实例，包括其bean名【变量 namedBean】</li>
	 *  <li>如果nameBean不为null,将nameBean返回出去</li>
	 *  <li>获取父工厂【变量 parent】</li>
	 *  <li>如果父工厂是AutowireCapableBeanFactory的实例,使用父工厂递归调用该方法来解析与requiredType唯一匹配的bean实例,
	 *  包括其bean名并将结果返回出去</li>
	 *  <li>在没能解析时，抛出没有此类BeanDefinition异常</li>
	 * </ol>
	 *
	 * @param requiredType type the bean must match; can be an interface or superclass
	 *                     -- 键入bean必需匹配；可以是接口或超类
	 * @param <T>          Bean对象的类型
	 * @return Bean名称加Bean实例的封装类
	 * @throws BeansException 如果无法创建该bean
	 */
	@Override
	public <T> NamedBeanHolder<T> resolveNamedBean(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		NamedBeanHolder<T> namedBean = resolveNamedBean(ResolvableType.forRawClass(requiredType), null, false);
		if (namedBean != null) {
			return namedBean;
		}
		BeanFactory parent = getParentBeanFactory();
		if (parent instanceof AutowireCapableBeanFactory acbf) {
			return acbf.resolveNamedBean(requiredType);
		}
		throw new NoSuchBeanDefinitionException(requiredType);
	}

	/**
	 * 解析与给定对象类型唯一匹配的bean实例，包括其bean名:
	 * <ol>
	 *  <li>如果requiredType为null，抛出异常</li>
	 *  <li>获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的候选Bean名(也包括Prototype级别的Bean对象,并
	 *  允许初始化lazy-init单例和由FactoryBeans创建的对象)【变量 candidateNames】</li>
	 *  <li>如果candidateNames不止一个:
	 *   <ol>
	 *    <li>定义一个用于保存可自动注入Bean名的集合，初始化长度为后续Bean名数组的长度【变量 autowireCandidates】</li>
	 *    <li>遍历候选Bean名称,元素为beanName:
	 *     <ol>
	 *      <li>如果本地工厂中BeanDefinition对象映射【beanDefinitionMap】中不存在beanName该键名 或者
	 *      该beanName所对应的BeanDefinition对象指定了该Bean对象可以自动注入:就将bean名添加到autowireCandidates中</li>
	 *     </ol>
	 *    </li>
	 *    <li>如果autowireCandidates不为空,将autowireCandidates转换成数组重新赋值给candidateNames</li>
	 *   </ol>
	 *  </li>
	 *  <li>如果candidateNames只有一个:
	 *   <ol>
	 *    <li>获取这唯一一个后续bean名【变量 beanName】</li>
	 *    <li>将beanName，何其对应的Bean对象一起封装到NameBeanHolder对象中，然后返回出去</li>
	 *   </ol>
	 *  </li>
	 *  <li>如果candidateNames不止一个:
	 *   <ol>
	 *    <li>定义一个用于存储候选Bean名和后续Bean对象/后续Bean类型的Map【变量 candidates】</li>
	 *    <li>遍历candidateNames,元素为beanName:
	 *     <ol>
	 *      <li>如果beanName在该BeanFactory的单例对象的高速缓存Map集合【DefaultListableBeanFactory.singletonObjects】中
	 *      且 没有生成Bean对象所需的构造函数参数:
	 *       <ol>
	 *        <li>获取beanName的Bean对象【变量 beanInstance】</li>
	 *        <li>将beanName和bean对象添加到candidates中(如果bean对象是NullBean实例，value则为null)</li>
	 *       </ol>
	 *      </li>
	 *      <li>否则，可以认为是Prototype级别的Bean对象:将beanName和beanName所对应的Bean Class对象添加到
	 *      candidates中</li>
	 *     </ol>
	 *    </li>
	 *    <li>在candidates中确定primary候选Bean名【变量 candidateName】</li>
	 *    <li>如果没有primary候选Bean名,获取candidates中具有Priority注解最高优先级的候选Bean名重新赋值给candidateName</li>
	 *    <li>如果candidateName不为null:
	 *     <ol>
	 *      <li>从candidates中获取candidateName对应的Bean对象【变量 beanInstance】</li>
	 *      <li>如果beanInstance为null 或者 benaInstance是Class对象:根据candidateName,requiredType的Class对象，
	 *      args获取对应Bean对象</li>
	 *      <li>将beanName，和beanInstance一起封装到NameBeanHolder对象中，然后返回出去</li>
	 *     </ol>
	 *    </li>
	 *    <li>如果没有设置，或者设置遇到非唯一Bean对象情况下直接抛出异常的时候:抛出 非唯一BenaDefinition异常</li>
	 *   </ol>
	 *  </li>
	 *  <li>在没有候选Bean对象的情况下，返回null</li>
	 * </ol>
	 *
	 * @param requiredType    键入bean必需匹配；可以是接口或超类
	 * @param args            用于构造requiredType所对应的Bean对象的参数，一般是指生成Bean对象所需的构造函数参数
	 * @param nonUniqueAsNull 遇到非唯一Bean对象情况下，如果为true将直接返回null，否则抛出异常
	 * @param <T>             Bean对象的类型
	 * @return Bean名称加Bean实例的封装类
	 * @throws BeansException 如果无法创建该bean
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	private <T> NamedBeanHolder<T> resolveNamedBean(
			ResolvableType requiredType, @Nullable Object[] args, boolean nonUniqueAsNull) throws BeansException {
		//如果requiredType为null，抛出异常
		Assert.notNull(requiredType, "Required type must not be null");
		//获取匹配type（包含子类）的bean（或由FactoryBean创建的对象）的候选Bean名称(也包括Prototype级别的Bean对象,并
		// 	允许初始化lazy-init单例和由FactoryBeans创建的对象)
		String[] candidateNames = getBeanNamesForType(requiredType);
		//如果候选Bean名不止一个
		if (candidateNames.length > 1) {
			//定义一个用于保存可自动注入Bean名的集合，初始化长度为后续Bean名数组的长度
			List<String> autowireCandidates = new ArrayList<>(candidateNames.length);
			//遍历候选Bean名称
			for (String beanName : candidateNames) {
				//如果本地工厂中BeanDefinition对象映射【beanDefinitionMap】中不存在beanName该键名 或者
				// 	该beanName所对应的BeanDefinition对象指定了该Bean对象可以自动注入
				if (!containsBeanDefinition(beanName) || getBeanDefinition(beanName).isAutowireCandidate()) {
					//将bean名添加到autowireCandidates中
					autowireCandidates.add(beanName);
				}
			}
			//如果autowireCandidates不为空
			if (!autowireCandidates.isEmpty()) {
				//将autowireCandidates转换成数组重新赋值给candidateNames
				candidateNames = StringUtils.toStringArray(autowireCandidates);
			}
		}

		//如果candidateNames只有一个
		if (candidateNames.length == 1) {
			return resolveNamedBean(candidateNames[0], requiredType, args);
		}
		//如果candidateNames不止一个
		else if (candidateNames.length > 1) {
			//定义一个用于存储候选Bean名和后续Bean对象/后续Bean类型的Map
			Map<String, Object> candidates = CollectionUtils.newLinkedHashMap(candidateNames.length);
			//遍历candidateNames
			for (String beanName : candidateNames) {
				//如果beanName在该BeanFactory的单例对象的高速缓存Map集合【DefaultListableBeanFactory.singletonObjects】中
				// 且 没有生成Bean对象所需的构造函数参数
				if (containsSingleton(beanName) && args == null) {
					//获取beanName的Bean对象
					Object beanInstance = getBean(beanName);
					//将beanName和bean对象添加到candidates中(如果bean对象是NullBean实例，value则为null)
					candidates.put(beanName, (beanInstance instanceof NullBean ? null : beanInstance));
				} else {
					//否则，可以认为是Prototype级别的Bean对象
					//将beanName和beanName所对应的Bean Class对象 添加到candidates中
					candidates.put(beanName, getType(beanName));
				}
			}
			//在candidates中确定primary候选Bean名
			String candidateName = determinePrimaryCandidate(candidates, requiredType.toClass());
			//如果没有primary候选Bean名
			if (candidateName == null) {
				//获取candidates中具有Priority注解最高优先级的候选Bean名重新赋值给candidateName
				candidateName = determineHighestPriorityCandidate(candidates, requiredType.toClass());
			}
			//如果candidateName不为null
			if (candidateName != null) {
				//从candidates中获取candidateName对应的Bean对象
				Object beanInstance = candidates.get(candidateName);
				if (beanInstance == null) {
					return null;
				}
				//如果beanInstance为null 或者 benaInstance是Class对象
				if (beanInstance instanceof Class) {
					//将beanName，和beanInstance一起封装到NameBeanHolder对象中，然后返回出去
					return resolveNamedBean(candidateName, requiredType, args);
				}
				return new NamedBeanHolder<>(candidateName, (T) beanInstance);
			}
			//如果没有设置，或者设置遇到非唯一Bean对象情况下直接抛出异常的时候
			if (!nonUniqueAsNull) {
				//抛出 非唯一BenaDefinition异常
				throw new NoUniqueBeanDefinitionException(requiredType, candidates.keySet());
			}
		}
		//在没有候选Bean对象的情况下，返回null
		return null;
	}

	@Nullable
	private <T> NamedBeanHolder<T> resolveNamedBean(
			String beanName, ResolvableType requiredType, @Nullable Object[] args) throws BeansException {
		//获取这唯一一个后续bean名
		Object bean = getBean(beanName, null, args);
		if (bean instanceof NullBean) {
			return null;
		}
		//将beanName，何其对应的Bean对象一起封装到NameBeanHolder对象中，然后返回出去
		return new NamedBeanHolder<>(beanName, adaptBeanInstance(beanName, bean, requiredType.toClass()));
	}

	/**
	 * 根据descriptor的依赖类型解析出与descriptor所包装的对象匹配的候选Bean对象:
	 * <ol>
	 *  <li>获取工厂的参数名发现器，设置到descriptor中。使得descriptor初始化基础方法参数的参数名发现。</li>
	 *  <li>【<b>当descriptor的依赖类型是Optional时</b>】:
	 *   <ol>
	 *    <li>如果descriptor的依赖类型为Optional类,创建Optional类型的符合descriptor要求的候选Bean对象并返回
	 *    出去</li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>当decriptord的依赖类型是ObjectFactory或者是ObjectProvider</b>】:
	 *   <ol>
	 *    <li>如果decriptord的依赖类型是ObjectFactory或者是ObjectProvider,新建一个
	 *    DependencyObjectProvider的实例并返回出去</li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>当decriptord的依赖类型是javax.inject.Provider</b>】:
	 *   <ol>
	 *    <li>如果依赖类型是javax.inject.Provider类,新建一个专门用于构建
	 *    javax.inject.Provider对象的工厂来构建创建Jse330Provider对象</li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>当descriptor需要延迟加载时</b>】:
	 *   <ol>
	 *    <li>尝试获取延迟加载代理对象【变量 result】</li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>当现在就需要得到候选Bean对象时</b>】:
	 *   <ol>
	 *    <li>如果result为null，即表示现在需要得到候选Bean对象,解析出与descriptor所包装的对象匹配
	 *    的候选Bean对象</li>
	 *   </ol>
	 *  </li>
	 *  <li>将与descriptor所包装的对象匹配的候选Bean对象【result】返回出去</li>
	 * </ol>
	 *
	 * @param descriptor         the descriptor for the dependency (field/method/constructor)
	 *                           -- 依赖项的描述符(字段/方法/构造函数)
	 * @param requestingBeanName the name of the bean which declares the given dependency
	 *                           -- 声明给定依赖项的bean名,即需要Field/MethodParamter所对应的bean对象来构建的Bean对象的Bean名
	 * @param autowiredBeanNames a Set that all names of autowired beans (used for
	 *                           resolving the given dependency) are supposed to be added to
	 *                           一个集合，所有自动装配的bean名(用于解决给定依赖关系)都应添加.即自动注入匹配成功的候选Bean名集合。
	 *                           【当autowiredBeanNames不为null，会将所找到的所有候选Bean对象添加到该集合中,以供调用方使用】
	 * @param typeConverter      the TypeConverter to use for populating arrays and collections
	 *                           -- 用于填充数组和集合的TypeConverter
	 * @return 解析的对象；如果找不到，则为null
	 * @throws BeansException 如果依赖项解析由于任何其他原因而失败
	 */
	@Override
	@Nullable
	public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName,
									@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {
		//获取工厂的参数名发现器，设置到descriptor中。使得descriptor初始化基础方法参数的参数名发现。此时，该方法实际上
		// 并没有尝试检索参数名称；它仅允许发现再应用程序调用getDependencyName时发生
		descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());
		//如果descriptor的依赖类型为Optional类
		if (Optional.class == descriptor.getDependencyType()) {
			//创建Optional类型的符合descriptor要求的候选Bean对象
			return createOptionalDependency(descriptor, requestingBeanName);
		}
		//ObjectFactory则只是一个普通的对象工厂接口。在Spring中主要两处用了它:
		// 1. org.springframework.beans.factory.config.Scope.get(String, ObjectFactory).这个方法的目的
		// 就是从对于的域中获取到指定名称的对象。为什么要传入一个objectFactory呢？主要是为了方便我们扩展自定义的域，
		// 而不是仅仅使用request，session等域。
		// 2. {@link org.springframework.beans.factory.config.ConfigurableListableBeanFactory#registerResolvableDependency(Class, Object)}
		//  autowiredValue这个参数可能就是一个ObjectFactory，主要是为了让注入点能够被延迟注入
		//ObjectProvider:ObjectFactory的一种变体，专门为注入点设置，允许程序选择和扩展的非唯一处理。
		// 	具体用法参考博客：https://blog.csdn.net/qq_41907991/article/details/105123387
		//如果decriptord的依赖类型是ObjectFactory或者是ObjectProvider
		else if (ObjectFactory.class == descriptor.getDependencyType() ||
				ObjectProvider.class == descriptor.getDependencyType()) {
			//DependencyObjectProvider:依赖对象提供者,用于延迟解析依赖项
			//新建一个DependencyObjectProvider的实例
			return new DependencyObjectProvider(descriptor, requestingBeanName);
		}
		// javaxInjectProviderClass有可能导致空指针，不过一般情况下，我们引用Spirng包的时候都有引入该类以防止空旨在
		//如果依赖类型是javax.inject.Provider类。
		else if (javaxInjectProviderClass == descriptor.getDependencyType()) {
			//Jse330Provider:javax.inject.Provider实现类.与DependencyObjectProvoid作用一样，也是用于延迟解析依赖
			// 	项，但它是使用javax.inject.Provider作为依赖 对象，以减少与Springd耦合
			//新建一个专门用于构建javax.inject.Provider对象的工厂来构建创建Jse330Provider对象
			return new Jsr330Factory().createDependencyProvider(descriptor, requestingBeanName);
		} else {
			//尝试获取延迟加载代理对象
			Object result = getAutowireCandidateResolver().getLazyResolutionProxyIfNecessary(
					descriptor, requestingBeanName);
			//如果result为null，即表示现在需要得到候选Bean对象
			if (result == null) {
				//解析出与descriptor所包装的对象匹配的候选Bean对象
				result = doResolveDependency(descriptor, requestingBeanName, autowiredBeanNames, typeConverter);
			}
			//将与descriptor所包装的对象匹配的候选Bean对象【result】返回出去
			return result;
		}
	}

	/**
	 * 解析出与descriptor所包装的对象匹配的候选Bean对象
	 * <ol>
	 *  <li>设置新得当前切入点对象，得到旧的当前切入点对象【变量 previousInjectionPoint】</li>
	 *  <li>【<b>尝试使用descriptor的快捷方法得到最佳候选Bean对象</b>】:
	 *   <ol>
	 *    <li>获取针对该工厂的这种依赖关系的快捷解析最佳候选Bean对象【变量 shortcut】</li>
	 *    <li>如果shortcut不为null，返回该shortcut</li>
	 *   </ol>
	 *  </li>
	 *  <li>获取descriptor的依赖类型【变量 type】</li>
	 *  <li>【<b>尝试使用descriptor的默认值作为最佳候选Bean对象</b>】:
	 *   <ol>
	 *    <li>使用此BeanFactory的自动装配候选解析器获取descriptor的默认值【变量 value】</li>
	 *    <li>如果value不为null:
	 *     <ol>
	 *      <li>如果value是String类型:
	 *       <ol>
	 *        <li>解析嵌套的值(如果value是表达式会解析出该表达式的值)【变量 strVal】</li>
	 *        <li>获取beanName的合并后RootBeanDefinition</li>
	 *        <li>让value引用评估bd中包含的value,如果strVal是可解析表达式，会对其进行解析.</li>
	 *       </ol>
	 *      </li>
	 *      <li>如果没有传入typeConverter,则引用工厂的类型转换器【变量 converter】</li>
	 *      <li>将value转换为type的实例对象并返回出去</li>
	 *      <li>捕捉 不支持操作异常:
	 *       <ol>
	 *        <li>如果descriptor有包装成员属性,根据descriptor包装的成员属性来将值转换为type然后返回出去</li>
	 *        <li>否则，根据descriptor包装的方法参数对象来将值转换为type然后返回出去</li>
	 *       </ol>
	 *      </li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>尝试针对descriptor所包装的对象类型是[stream,数组,Collection类型且对象类型是接口,Map]的情况，
	 *  进行解析与依赖类型匹配的候选Bean对象</b>】:
	 *   <ol>
	 *    <li>针对descriptor所包装的对象类型是[stream,数组,Collection类型且对象类型是接口,Map]的情况，进行解析与依赖类型匹配的 候选Bean对象，
	 *    并将其封装成相应的依赖类型对象【{@link #resolveMultipleBeans(DependencyDescriptor, String, Set, TypeConverter)}】</li>
	 *    <li>如果multipleBeans不为null,将multipleBeans返回出去</li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>尝试与type匹配的唯一候选bean对象</b>】:
	 *   <ol>
	 *    <li>查找与type匹配的候选bean对象,构建成Map，key=bean名,val=Bean对象【变量 matchingBeans】</li>
	 *    <li>如果没有候选bean对象:
	 *     <ol>
	 *      <li>如果descriptor需要注入,抛出NoSuchBeanDefinitionException或BeanNotOfRequiredTypeException以解决不可
	 *      解决的依赖关系</li>
	 *      <li>返回null，表示么有找到候选Bean对象</li>
	 *     </ol>
	 *    </li>
	 *    <li>定义用于存储唯一的候选Bean名变量【变量 autowiredBeanName】</li>
	 *    <li>定义用于存储唯一的候选Bean对象变量【变量 instanceCandidate】</li>
	 *    <li>如果候选Bean对象Map不止有一个:
	 *     <ol>
	 *      <li>让autowiredBeanName引用candidates中可以自动注入的最佳候选Bean名称</li>
	 *      <li>如果autowiredBeanName为null:
	 *       <ol>
	 *        <li>如果descriptor需要注入 或者 type不是数组/集合类型，让descriptor尝试选择其中一个实例，默认实现是
	 *        抛出NoUniqueBeanDefinitionException.</li>
	 *        <li>返回null，表示找不到最佳候选Bean对象</li>
	 *       </ol>
	 *      </li>
	 *      <li>让instanceCandidate引用autowiredBeanName对应的候选Bean对象</li>
	 *     </ol>
	 *    </li>
	 *    <li>否则，获取matchingBeans唯一的元素【变量 entry】:
	 *     <ol>
	 *       <li>让autowireBeanName引用该entry的候选bean名</li>
	 *       <li>让instanceCandidate引用该entry的候选bean对象</li>
	 *     </ol>
	 *    </li>
	 *    <li>如果候选bean名不为null，将autowiredBeanName添加到autowiredBeanNames中</li>
	 *    <li>如果instanceCandidate是Class实例,让instanceCandidate引用 descriptor对autowiredBeanName解析
	 *    为该工厂的Bean实例</li>
	 *    <li>定义一个result变量，用于存储最佳候选Bean对象</li>
	 *    <li>如果reuslt是NullBean的实例:
	 *     <ol>
	 *       <li>如果descriptor需要注入,抛出NoSuchBeanDefinitionException或BeanNotOfRequiredTypeException
	 *       以解决不可 解决的依赖关系</li>
	 *       <li>返回null，表示找不到最佳候选Bean对象</li>
	 *     </ol>
	 *    </li>
	 *    <li>如果result不是type的实例,抛出Bean不是必需类型异常</li>
	 *    <li>返回最佳候选Bean对象【result】</li>
	 *   </ol>
	 *  </li>
	 *  <li>【finally】设置上一个切入点对象</li>
	 * </ol>
	 *
	 * @param descriptor         依赖项的描述符(字段/方法/构造函数)
	 * @param beanName           要依赖的Bean名,即需要Field/MethodParameter所对应的bean对象来构建的Bean对象的Bean名
	 * @param autowiredBeanNames 一个集合，所有自动装配的bean名(用于解决给定依赖关系)都应添加.即自动注入匹配成功的候选Bean名集合。
	 *                           【当autowiredBeanNames不为null，会将所找到的所有候选Bean对象添加到该集合中,以供调用方使用】
	 * @param typeConverter      用于填充数组和集合的TypeConverter
	 * @return 解析的对象；如果找不到，则为null
	 * @throws BeansException 如果依赖项解析由于任何其他原因而失败
	 */
	@Nullable
	public Object doResolveDependency(DependencyDescriptor descriptor, @Nullable String beanName,
									  @Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) throws BeansException {
		//设置新得当前切入点对象，得到旧的当前切入点对象
		InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
		try {
			//尝试使用descriptor的快捷方法得到最近候选Bean对象
			//resolveShortcut：解决针对给定工厂的这种依赖关系的快捷方式，例如，考虑一些预先解决的信息
			//尝试调用该工厂解决这种依赖关系的快捷方式来获取beanName对应的bean对象,默认返回null
			//获取针对该工厂的这种依赖关系的快捷解析最佳候选Bean对象
			Object shortcut = descriptor.resolveShortcut(this);
			//如果shortcut不为null，返回该shortcut
			if (shortcut != null) {
				return shortcut;
			}
			//获取descriptor的依赖类型
			Class<?> type = descriptor.getDependencyType();
			//尝试使用descriptor的默认值作为最近候选Bean对象
			//使用此BeanFactory的自动装配候选解析器获取descriptor的默认值
			Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);
			//如果默认值不为null
			if (value != null) {
				//如果value是String类型
				if (value instanceof String) {
					//解析嵌套的值(如果value是表达式会解析出该表达式的值)
					String strVal = resolveEmbeddedValue((String) value);
					//获取beanName的合并后RootBeanDefinition
					BeanDefinition bd = (beanName != null && containsBean(beanName) ?
							getMergedBeanDefinition(beanName) : null);
					//评估bd中包含的value,如果strVal是可解析表达式，会对其进行解析.
					value = evaluateBeanDefinitionString(strVal, bd);
				}
				//如果没有传入typeConverter,则引用工厂的类型转换器
				TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
				try {
					//将value转换为type的实例对象
					return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());
				}
				//捕捉 不支持操作异常
				catch (UnsupportedOperationException ex) {
					// A custom TypeConverter which does not support TypeDescriptor resolution...
					// 自定义TypeConverter,不支持TypeDescriptor解析
					//descriptor.getField():返回所包装的成员属性，仅在当前对象用于包装成员属性时返回非null<
					//如果descriptor有包装成员属性
					return (descriptor.getField() != null ?
							//根据包装的成员属性来将值转换为所需的类型
							converter.convertIfNecessary(value, type, descriptor.getField()) :
							//根据包装的方法参数对象来将值转换为所需的类型
							converter.convertIfNecessary(value, type, descriptor.getMethodParameter()));
				}
			}
			//尝试针对descriptor所包装的对象类型是[stream,数组,Collection类型且对象类型是接口,Map]的情况，进行解析与依赖类型匹配的候选Bean对象
			//针对descriptor所包装的对象类型是[stream,数组,Collection类型且对象类型是接口,Map]的情况，进行解析与依赖类型匹配的 候选Bean对象，
			// 并将其封装成相应的依赖类型对象
			Object multipleBeans = resolveMultipleBeans(descriptor, beanName, autowiredBeanNames, typeConverter);
			//如果multipleBeans不为null
			if (multipleBeans != null) {
				//将multipleBeans返回出去
				return multipleBeans;
			}
			//尝试与type匹配的唯一候选bean对象
			//查找与type匹配的候选bean对象,构建成Map，key=bean名,val=Bean对象
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			//如果没有候选bean对象
			if (matchingBeans.isEmpty()) {
				//如果descriptor需要注入
				if (isRequired(descriptor)) {
					//抛出NoSuchBeanDefinitionException或BeanNotOfRequiredTypeException以解决不可 解决的依赖关系
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				//返回null，表示么有找到候选Bean对象
				return null;
			}

			//定义用于存储唯一的候选Bean名变量
			String autowiredBeanName;
			//定义用于存储唯一的候选Bean对象变量
			Object instanceCandidate;

			//如果候选Bean对象Map不止有一个
			if (matchingBeans.size() > 1) {
				//确定candidates中可以自动注入的最佳候选Bean名称
				autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
				//如果autowiredBeanName为null
				if (autowiredBeanName == null) {
					//descriptor需要注入 或者 type不是数组/集合类型
					if (isRequired(descriptor) || !indicatesMultipleBeans(type)) {
						//让descriptor尝试选择其中一个实例，默认实现是抛出NoUniqueBeanDefinitionException.
						return descriptor.resolveNotUnique(descriptor.getResolvableType(), matchingBeans);
					} else {
						// In case of an optional Collection/Map, silently ignore a non-unique case:
						// possibly it was meant to be an empty collection of multiple regular beans
						// (before 4.3 in particular when we didn't even look for collection beans).
						// 如果是可选的Collection/Map,则静默忽略一个非唯一情况：
						// 可能是多个常规bean的空集合
						// (尤其是在4.3之前，设置在我们没有寻找collection bean的时候 )
						return null;
					}
				}
				//获取autowiredBeanName对应的候选Bean对象
				instanceCandidate = matchingBeans.get(autowiredBeanName);
			} else {
				// We have exactly one match. 我们刚好只有一个匹配
				//这个时候matchingBeans不会没有元素的，因为前面已经检查了
				//获取matchingBeans唯一的元素
				Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
				//让autowireBeanName引用该元素的候选bean名
				autowiredBeanName = entry.getKey();
				//让instanceCandidate引用该元素的候选bean对象
				instanceCandidate = entry.getValue();
			}
			//如果候选bean名不为null，
			if (autowiredBeanNames != null) {
				//将autowiredBeanName添加到autowiredBeanNames中，又添加一次？
				autowiredBeanNames.add(autowiredBeanName);
			}
			//如果instanceCandidate是Class实例
			if (instanceCandidate instanceof Class) {
				//让instanceCandidate引用 descriptor对autowiredBeanName解析为该工厂的Bean实例
				instanceCandidate = descriptor.resolveCandidate(autowiredBeanName, type, this);
			}
			//定义一个result变量，用于存储最佳候选Bean对象
			Object result = instanceCandidate;
			//如果result是NullBean的实例
			if (result instanceof NullBean) {
				//如果descriptor需要注入
				if (isRequired(descriptor)) {
					//抛出NoSuchBeanDefinitionException或BeanNotOfRequiredTypeException以解决不可 解决的依赖关系
					raiseNoMatchingBeanFound(type, descriptor.getResolvableType(), descriptor);
				}
				//返回null，表示找不到最佳候选Bean对象
				result = null;
			}
			//如果result不是type的实例
			if (!ClassUtils.isAssignableValue(type, result)) {
				//抛出Bean不是必需类型异常
				throw new BeanNotOfRequiredTypeException(autowiredBeanName, type, instanceCandidate.getClass());
			}
			//返回最佳候选Bean对象【result】
			return result;
		} finally {
			//设置上一个切入点对象
			ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
		}
	}

	/**
	 * 针对descriptor所包装的对象类型是[stream,数组,Collection类型且对象类型是接口,Map]的情况，进行解析与依赖类型匹配的
	 * 候选Bean对象，并将其封装成相应的依赖类型对象
	 * <ol>
	 *  <li>获取包装的参数/字段的声明的(非通用)类型【变量 type】</li>
	 *  <li>【<b>当descriptor所包装的对象是Stream类型</b>】:
	 *   <ol>
	 *    <li>如果描述符是Stream依赖项描述符:
	 *     <ol>
	 *      <li>查找与valueType匹配的候选bean对象;构建成Map，key=bean名,val=Bean对象【变量 matchingBeans】</li>
	 *      <li>自动注入匹配成功的候选Bean名集合不为null,将所有的自动注入匹配成功的候选Bean名添加到autowiredBeanNames</li>
	 *      <li>取出除Bean对象为NullBean以外的所有候选Bean名称的Bean对象【变量 stream】</li>
	 *      <li>如果descriptor需要排序,根据matchingBean构建排序比较器，交由steam进行排序</li>
	 *      <li>返回已排好序且已存放除Bean对象为NullBean以外的所有候选Bean名称的Bean对象的stream对象【变量 stream】</li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>当descriptor所包装的对象是数组类型</b>】：
	 *   <ol>
	 *    <li>如果依赖类型是数组类型:
	 *     <ol>
	 *      <li>获取type的元素Class对象【变量 componentType】</li>
	 *      <li>获取descriptor包装的参数/字段所构建出来的ResolvableType对象【变量 resolvableType】</li>
	 *      <li>让resolvableType解析出的对应的数组Class对象，如果解析失败，就引用type【变量 resolvedArrayType】</li>
	 *      <li>如果resolvedArrayType与type不是同一个Class对象,componentType就引用resolvableType解析处理的元素Class对象</li>
	 *      <li>如果没有元素Class对象，就返回null，表示获取不到候选bean对象</li>
	 *      <li>查找与valueType匹配的候选bean对象;构建成Map，key=bean名,val=Bean对象【变量 matchingBeans】</li>
	 *      <li>如果没有候选Bean对象,返回null，表示获取不到候选bean对象</li>
	 *      <li>自动注入匹配成功的候选Bean名集合不为null,将所有的自动注入匹配成功的候选Bean名添加到autowiredBeanNames</li>
	 *      <li>如果有传入类型转换器就引用传入的类型转换器，否则获取此BeanFactory使用的类型转换器</li>
	 *      <li>将所有候选Bean对象转换为resolvedArrayType类型【变量 result】</li>
	 *      <li>如果result是数组实例:
	 *       <ol>
	 *        <li>构建依赖比较器,用于对matchingBean的所有bean对象进行优先级排序【变量 comparator】</li>
	 *        <li>如果比较器不为null,使用comparator对result数组进行排序</li>
	 *       </ol>
	 *      </li>
	 *      <li>返回该候选对象数组【result】</li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>如果依赖类型属于Collection类型 且 依赖类型是否接口</b>】:
	 *   <ol>
	 *    <li>将descriptor所包装的参数/字段构建出来的ResolvableType对象解析成Collection类型，然后解析出其
	 *    泛型参数的Class对象【变量 elementType】</li>
	 *    <li>如果元素类型为null,返回null，表示获取不到候选bean对象</li>
	 *    <li>查找与valueType匹配的候选bean对象;构建成Map，key=bean名,val=Bean对象【变量 matchingBeans】</li>
	 *    <li>如果没有候选bean对象，返回null，表示获取不到候选bean对象</li>
	 *    <li>自动注入匹配成功的候选Bean名集合不为null,将所有的自动注入匹配成功的候选Bean名添加到autowiredBeanNames</li>
	 *    <li>如果有传入类型转换器就引用传入的类型转换器，否则获取此BeanFactory使用的类型转换器</li>
	 *    <li>将所有候选Bean对象转换为resolvedArrayType类型【变量 result】</li>
	 *    <li>如果result是List实例:
	 *     <ol>
	 *      <li>构建依赖比较器,用于对matchingBean的所有bean对象进行优先级排序【变量 comparator】</li>
	 *      <li>如果比较器不为null,使用comparator对result数组进行排序</li>
	 *     </ol>
	 *    </li>
	 *    <li>返回该候选对象数组【result】</li>
	 *   </ol>
	 *  </li>
	 *  <li>【<b>如果依赖类型是Map类型</b>】：
	 *   <ol>
	 *    <li>将descriptor所包装的参数/字段构建出来的ResolvableType对象解析成Map类型【变量 mapType】</li>
	 *    <li>解析出第1个泛型参数的Class对象,即key的Class对象【变量 keyType】</li>
	 *    <li>如果keyType不是String类型,返回null，表示获取不到候选bean对象</li>
	 *    <li>解析出第2个泛型参数的Class对象,即value的Class对象【变量 valueType】</li>
	 *    <li>如果keyType为null，即解析不出value的Class对象或者是根本没有value的Class对象,
	 *    返回null，表示获取不到候选bean对象</li>
	 *    <li>查找与valueType匹配的候选bean对象;构建成Map，key=bean名,val=Bean对象【变量 matchingBeans】</li>
	 *    <li>如果没有候选bean对象,返回null，表示获取不到候选bean对象</li>
	 *    <li>自动注入匹配成功的候选Bean名集合不为null,将所有的自动注入匹配成功的候选Bean名添加到autowiredBeanNames</li>
	 *    <li>返回候选的Bean对象Map【matchingBeans】</li>
	 *   </ol>
	 *  </li>
	 * </ol>
	 *
	 * @param descriptor         依赖项的描述符(字段/方法/构造函数)
	 * @param beanName           声明给定依赖项的bean名
	 * @param autowiredBeanNames 一个集合，所有自动装配的bean名(用于解决给定依赖关系)都应添加.即自动注入匹配成功的候选Bean名集合。
	 *                           【当autowiredBeanNames不为null，会将所找到的所有候选Bean对象添加到该集合中,以供调用方使用】
	 * @param typeConverter      用于填充数组和集合的TypeConverter
	 * @return 由候选Bean对象组成对象，该对象与descriptor的依赖类型相同;如果descriptor的依赖类型不是
	 * [stream,数组,Collection类型且对象类型是接口,Map],又或者解析不出相应的依赖类型，又或者拿不到候选Bean对象都会导致返回null
	 */
	@Nullable
	private Object resolveMultipleBeans(DependencyDescriptor descriptor, @Nullable String beanName,
										@Nullable Set<String> autowiredBeanNames, @Nullable TypeConverter typeConverter) {

		//获取包装的参数/字段的声明的(非通用)类型
		final Class<?> type = descriptor.getDependencyType();
		//如果描述符是Stream依赖项描述符
		if (descriptor instanceof StreamDependencyDescriptor) {
			//查找与valueType匹配的候选bean对象;构建成Map，key=bean名,val=Bean对象
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			//自动注入匹配成功的候选Bean名集合不为null
			if (autowiredBeanNames != null) {
				//将所有的自动注入匹配成功的候选Bean名添加到autowiredBeanNames
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			//取出除Bean对象为NullBean以外的所有候选Bean名称的Bean对象
			Stream<Object> stream = matchingBeans.keySet().stream()
					.map(name ->
							//将name解析为该Bean工厂的Bean实例
							descriptor.resolveCandidate(name, type, this))
					//只要收集bean对象不为NullBean对象
					.filter(bean -> !(bean instanceof NullBean));
			//如果decriptor需要排序
			if (((StreamDependencyDescriptor) descriptor).isOrdered()) {
				//根据matchingBean构建排序比较器，交由steam进行排序
				stream = stream.sorted(adaptOrderComparator(matchingBeans));
			}
			//返回已排好序且已存放除Bean对象为NullBean以外的所有候选Bean名称的Bean对象的stream对象
			return stream;
		}
		//如果依赖类型是数组类型
		else if (type.isArray()) {
			//获取type的元素Class对象
			Class<?> componentType = type.getComponentType();
			//获取decriptor包装的参数/字段所构建出来的ResolvableType对象
			ResolvableType resolvableType = descriptor.getResolvableType();
			//让resolvableType解析出的对应的数组Class对象，如果解析失败，就引用type
			Class<?> resolvedArrayType = resolvableType.resolve(type);
			//如果resolvedArrayType与type不是同一个Class对象
			if (resolvedArrayType != type) {
				//componentType就引用resolvableType解析处理的元素Class对象
				componentType = resolvableType.getComponentType().resolve();
			}
			//如果没有元素Class对象，就返回null，表示获取不到候选bean对象
			if (componentType == null) {
				return null;
			}
			//MultiElemetDesciptor:具有嵌套元素的多元素声明的依赖描述符，表示集合/数组依赖
			//查找与valueType匹配的候选bean对象;构建成Map，key=bean名,val=Bean对象
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, componentType,
					new MultiElementDescriptor(descriptor));
			//如果没有候选Bean对象
			if (matchingBeans.isEmpty()) {
				//返回null，表示获取不到候选bean对象
				return null;
			}
			//自动注入匹配成功的候选Bean名集合不为null
			if (autowiredBeanNames != null) {
				//将所有的自动注入匹配成功的候选Bean名添加到autowiredBeanNames
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			//如果有传入类型转换器就引用传入的类型转换器，否则获取此BeanFactory使用的类型转换器
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			//将所有候选Bean对象转换为resolvedArrayType类型
			Object result = converter.convertIfNecessary(matchingBeans.values(), resolvedArrayType);
			//如果result是数组实例
			if (result instanceof Object[]) {
				//构建依赖比较器,用于对matchingBean的所有bean对象进行优先级排序
				Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
				//如果比较器不为null
				if (comparator != null) {
					//使用comparator对result数组进行排序
					Arrays.sort((Object[]) result, comparator);
				}
			}
			//返回该候选对象数组
			return result;
		}
		//如果依赖类型属于Collection类型 且 依赖类型是否接口
		else if (Collection.class.isAssignableFrom(type) && type.isInterface()) {
			//将descoptor所包装的参数/字段构建出来的ResolvableType对象解析成Collectionl类型，然后
			// 解析出其泛型参数的Class对象
			Class<?> elementType = descriptor.getResolvableType().asCollection().resolveGeneric();
			//如果元素类型为null
			if (elementType == null) {
				//返回null，表示获取不到候选bean对象
				return null;
			}
			//查找与valueType匹配的候选bean对象;构建成Map，key=bean名,val=Bean对象
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, elementType,
					new MultiElementDescriptor(descriptor));
			//如果没有候选bean对象，
			if (matchingBeans.isEmpty()) {
				//返回null，表示获取不到候选bean对象
				return null;
			}
			//自动注入匹配成功的候选Bean名集合不为null
			if (autowiredBeanNames != null) {
				//将所有的自动注入匹配成功的候选Bean名添加到autowiredBeanNames
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			//如果有传入类型转换器就引用传入的类型转换器，否则获取此BeanFactory使用的类型转换器
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			//将所有候选Bean对象转换为resolvedArrayType类型
			Object result = converter.convertIfNecessary(matchingBeans.values(), type);
			//如果result是List实例
			if (result instanceof List) {
				//构建依赖比较器,用于对matchingBean的所有bean对象进行优先级排序
				Comparator<Object> comparator = adaptDependencyComparator(matchingBeans);
				//如果比较器不为null
				if (comparator != null) {
					//使用comparator对result数组进行排序
					((List<?>) result).sort(comparator);
				}
			}
			//返回该候选对象数组
			return result;
		}
		//如果依赖类型是Map类型
		else if (Map.class == type) {
			//将descoptor所包装的参数/字段构建出来的ResolvableType对象解析成Map类型
			ResolvableType mapType = descriptor.getResolvableType().asMap();
			//解析出第1个泛型参数的Class对象,即key的Class对象
			Class<?> keyType = mapType.resolveGeneric(0);
			//如果keyType不是String类型
			if (String.class != keyType) {
				//返回null，表示获取不到候选bean对象
				return null;
			}
			//解析出第2个泛型参数的Class对象,即value的Class对象
			Class<?> valueType = mapType.resolveGeneric(1);
			//如果keyType为null，即解析不出value的Class对象或者是根本没有value的Class对象
			if (valueType == null) {
				//返回null，表示获取不到候选bean对象
				return null;
			}
			//查找与valueType匹配的候选bean对象;构建成Map，key=bean名,val=Bean对象
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, valueType,
					new MultiElementDescriptor(descriptor));
			//如果没有候选bean对象
			if (matchingBeans.isEmpty()) {
				//返回null，表示获取不到候选bean对象
				return null;
			}
			//自动注入匹配成功的候选Bean名集合不为null
			if (autowiredBeanNames != null) {
				//将所有的自动注入匹配成功的候选Bean名添加到autowiredBeanNames
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			//返回候选的Bean对象Map
			return matchingBeans;
		} else {
			//返回null，表示获取不到候选bean对象
			return null;
		}
	}

	/**
	 * descriptor是否确实需要注入
	 */
	private boolean isRequired(DependencyDescriptor descriptor) {
		//使用此BeanFactory的自动装配候选解析器确定descriptor确实需要注入并将结果返回出去
		return getAutowireCandidateResolver().isRequired(descriptor);
	}

	/**
	 * type是否是数组/集合类型
	 *
	 * @param type 要检查的类型
	 */
	private boolean indicatesMultipleBeans(Class<?> type) {
		//如果type是数组类型 或者 (type是接口而且type是Colletion类型或者type是Map类型)返回true，表示是数组/集合类型；
		// 否则返回false，表示不是数组/集合类型
		return (type.isArray() || (type.isInterface() &&
				(Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type))));
	}


	/**
	 * 构建依赖比较器,用于对matchingBean的所有bean对象进行优先级排序
	 * <ol>
	 *  <li>获取此BeanFactory的依赖关系比较器【变量 comparator】</li>
	 *  <li>如果comparator是OrderComparator实例,创建工厂感知排序源提供者实例
	 *  【FactoryAwareOrderSourceProvider】并让comparator引用它,然后返回出去</li>
	 *  <li>返回此BeanFactory的依赖关系比较器【comparator】</li>
	 * </ol>
	 *
	 * @param matchingBeans 要排序的Bean对象Map，key=Bean名,value=Bean对象
	 * @return 依赖比较器, 可能是 {@link OrderComparator} 实例
	 */
	@Nullable
	private Comparator<Object> adaptDependencyComparator(Map<String, ?> matchingBeans) {
		//获取此BeanFactory的依赖关系比较器
		Comparator<Object> comparator = getDependencyComparator();
		//如果comparator是OrderComparator实例
		if (comparator instanceof OrderComparator) {
			//创建工厂感知排序源提供者实例【FactoryAwareOrderSourceProvider】并让comparator引用它,然后返回出去
			return ((OrderComparator) comparator).withSourceProvider(
					createFactoryAwareOrderSourceProvider(matchingBeans));
		} else {
			//返回此BeanFactory的依赖关系比较器
			return comparator;
		}
	}

	/**
	 * 构建排序比较器,用于对matchingBean的所有bean对象进行优先级排序
	 * <ol>
	 *  <li>获取该工厂的依赖关系比较器【变量 dependencyComparator】，SpringBoot默认
	 *  使用【{@link org.springframework.core.annotation.AnnotationAwareOrderComparator}】</li>
	 *  <li>如果dependencyComparator是OrderComparator的实例,就让comparator引用该实例，
	 *  否则使用OrderComparator的默认实例【变量 comparator】</li>
	 *  <li>创建工厂感知排序源提供者实例【FactoryAwareOrderSourceProvider】,并让comparator引用它</li>
	 *  <li>返回比较器【comparator】</li>
	 * </ol>
	 *
	 * @param matchingBeans 要排序的Bean对象Map，key=Bean名,value=Bean对象
	 * @return 排序比较器，一定是 {@link OrderComparator} 实例
	 */
	private Comparator<Object> adaptOrderComparator(Map<String, ?> matchingBeans) {
		//获取该工厂的依赖关系比较器，SpringBoot默认使用 AnnotationAwareOrderComparator
		Comparator<Object> dependencyComparator = getDependencyComparator();
		//如果dependencyComparator是OrderComparator的实例,就让comparator引用该实例，否则使用OrderComparator的默认实例
		OrderComparator comparator = (dependencyComparator instanceof OrderComparator ?
				(OrderComparator) dependencyComparator : OrderComparator.INSTANCE);
		//创建工厂感知排序源提供者实例【FactoryAwareOrderSourceProvider】,并让comparator引用它
		return comparator.withSourceProvider(createFactoryAwareOrderSourceProvider(matchingBeans));
	}

	/**
	 * 创建工厂感知排序源提供者实例：
	 * <ol>
	 *  <li>定义一个IdentityHashMap对象,用于保存要排序的Bean对象，key为Bean对象，value为bean名【变量 instancesToBeanNames】</li>
	 *  <li>将beans的所有key/value添加到instancesToBeanNames中</li>
	 *  <li>新建一个工厂感知排序源提供者实例用于提供要排序对象的Order来源,用于代替obj获取优先级值
	 *  【{@link FactoryAwareOrderSourceProvider}】；并返回出去</li>
	 * </ol>
	 *
	 * @param beans 要排序的Bean对象Map，key=Bean名,value=Bean对象
	 * @return FactoryAwareOrderSourceProvider(用于提供要排序对象的Order来源, 用于代替obj获取优先级值)
	 */
	private OrderComparator.OrderSourceProvider createFactoryAwareOrderSourceProvider(Map<String, ?> beans) {
		//IdentityHashMap:允许"相同"的key保存进来,所谓的"相同"是指key的hashCode()和equal()的返回值相同.在使用get()的时候
		// 需要保证与key是同一个对象(即地址值相同)才能获取到对应的value.因为IdentityHashMap比较key值时，直接使用的是==
		//定义一个IdentityHashMap对象,用于保存要排序的Bean对象，key为Bean对象，value为bean名
		IdentityHashMap<Object, String> instancesToBeanNames = new IdentityHashMap<>();
		//将beans的所有key/value添加到instancesToBeanNames中
		beans.forEach((beanName, instance) -> instancesToBeanNames.put(instance, beanName));
		//新建一个工厂感知排序源提供者实例：提供要排序对象的Order来源,用于代替obj获取优先级值。主要Order来源:
		//  1. obj对应的Bean名的合并后RootBeanDefinition的工厂方法对象
		//  2. obj对应的Bean名的合并后RootBeanDefinition的目标类型
		return new FactoryAwareOrderSourceProvider(instancesToBeanNames);
	}


	/**
	 * <p>查找与type匹配的候选bean对象,构建成Map，key=bean名,val=Bean对象【在自动装配指定bean期间调用】:
	 *  <ol>
	 *   <li>获取requiredType的所有bean名,包括父级工厂中定义的名称【变量 candidateNames】</li>
	 *   <li>定义用于保存匹配requiredType的bean名和其实例对象的Map，即匹配成功的候选Map【变量 result】</li>
	 *   <li>【<b>从 存放着手动显示注册的依赖项类型-相应的自动装配值的缓存【{@link #resolvableDependencies}】中匹配候选</b>】:
	 *    <ol>
	 *     <li>遍历resolvableDependencies,元素classObjectEntry:
	 *      <ol>
	 *       <li>取出依赖项类型【变量 autowiringType】</li>
	 *       <li>如果autowiringType是属于requiredType的实例:
	 *        <ol>
	 *         <li>取出autowiringType对应的实例对象【变量 autowiringValue】</li>
	 *         <li>根据requiredType解析autowiringValue,并针对autowiringValue是ObjectFactory的情况进行解析,将解析出来的值
	 *         重新赋值给autowiringValue</li>
	 *         <li>如果autowiringValue是requiredType类型,就根据autowiringValue构建出唯一
	 *         ID与autowiringValue绑定到result中,然后跳槽循环</li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>常规匹配候选(beanDefinition是否允许依赖注入,泛型类型是否匹配,限定符注解
	 *   /限定符信息是否匹配)</b>】:
	 *    <ol>
	 *     <li>遍历candidateNames,元素candidate:
	 *      <ol>
	 *       <li>如果beanName与candidateName所对应的Bean对象不是同一个且candidate可以自动注入,
	 *       添加一个条目在result中:一个bean实例(如果可用)或仅一个已解析的类型</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>找不到候选时，就采用将回退模式(在回退模式下，候选Bean具有无法解析的泛型 || 候选Bean的Class
	 *   对象是Properties类对象时，都允许成为该描述符的可自动注入对象)尽可能的匹配到候选，一般情况
	 *   下不会出现回退情况,除非代码非常糟糕</b>】:
	 *    <ol>
	 *     <li>如果result为空:
	 *      <ol>
	 *       <li>requiredType是否是数组/集合类型的标记【变量multiple】</li>
	 *       <li>获取desciptord的一个旨在用于回退匹配变体【遍历 fallbackDescriptor】</li>
	 *       <li>【<b>先尝试匹配候选bean名符合允许回退匹配的依赖描述符的自动依赖条件且(依赖类型不是集合/数组
	 *       或者描述符指定限定符)的候选Bean对象</b>】:
	 *        <ol>
	 *         <li>遍历candidateNames,元素candidate:
	 *          <ol>
	 *           <li>如果beanName与candidateName所对应的Bean对象不是同一个 且 candidate可以自动注入 且
	 *           (type不是数组/集合类型或者 desciptor有@Qualifier注解或qualifier标准修饰),
	 *           就添加一个条目在result中:一个bean实例(如果可用)或仅一个已解析的类型</li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li>【<b>降低匹配精度:满足下面条件即可</b>】
	 *        <ul>
	 *         <li>除beanName符合描述符依赖类型不是数组/集合</li>
	 *         <li>如果beanName与candidateName所对应的Bean对象不是同一个</li>
	 *         <li>(descriptor不是集合依赖或者beanName与candidate不相同) </li>
	 *         <li>候选bean名符合允许回退匹配的依赖描述符的自动依赖条件 </li>
	 *        </ul>
	 *        <ol>
	 *         <li>如果result为空且requiredType不是数组/集合类型或者
	 *          <ol>
	 *           <li>遍历candidateNames,元素candidate:
	 *            <ol>
	 *             <li>如果beanName与candidateName所对应的Bean对象不是同一个 且 (descriptor不是
	 *             MultiElementDescriptor实例(即集合依赖)或者beanName不等于candidate)
	 *             且 candidate可以自动注入,添加一个条目在result中:一个bean实例(如果可用)
	 *             或仅一个已解析的类型</li>
	 *            </ol>
	 *           </li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>返回匹配成功的后续Bean对象【result】</li>
	 *  </ol>
	 * </p>
	 * Find bean instances that match the required type.
	 * Called during autowiring for the specified bean.
	 * <p>查找与所需类型匹配的bean实例。在自动装配指定bean期间调用</p>
	 *
	 * @param beanName     the name of the bean that is about to be wired
	 *                     -- 即将被连线的bean名，要依赖的bean名(不是指desciptor的所包装的Field/MethodParamater的依赖类型的bean名，
	 *                     而是指需要Field/MethodParamter所对应的bean对象来构建的Bean对象的Bean名)
	 * @param requiredType the actual type of bean to look for
	 *                     (may be an array component type or collection element type)
	 *                     -- 要查找的bean的实际类型(可以是数组组件或集合元素类型),descriptor的依赖类型
	 * @param descriptor   the descriptor of the dependency to resolve
	 *                     -- 要解析的依赖项的描述符
	 * @return a Map of candidate names and candidate instances that match
	 * the required type (never {@code null})
	 * -- 匹配所需类型的候选名称和候选实例的映射(从不为null)
	 * @throws BeansException in case of errors -- 如果有错误
	 * @see #autowireByType
	 * @see #autowireConstructor
	 */
	protected Map<String, Object> findAutowireCandidates(
			@Nullable String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {
		//获取requiredType的所有bean名,包括父级工厂中定义的名称
		String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
				this, requiredType, true, descriptor.isEager());
		//定义用于保存匹配requiredType的bean名和其实例对象的Map，即匹配成功的候选Map
		Map<String, Object> result = new LinkedHashMap<>(candidateNames.length);
		//从 存放着手动显示注册的依赖项类型-相应的自动装配值的缓存 中匹配候选
		//遍历 从依赖项类型映射到相应的自动装配值缓存
		for (Map.Entry<Class<?>, Object> classObjectEntry : this.resolvableDependencies.entrySet()) {
			//取出依赖项类型
			Class<?> autowiringType = classObjectEntry.getKey();
			//如果autowiringType是属于requiredType的实例
			if (autowiringType.isAssignableFrom(requiredType)) {
				//取出autowiringType对应的实例对象
				Object autowiringValue = classObjectEntry.getValue();
				//根据requiredType解析autowiringValue,并针对autowiringValue是ObjectFactory的情况进行解析
				autowiringValue = AutowireUtils.resolveAutowiringValue(autowiringValue, requiredType);
				//如果autowiringValue是requiredType类型
				if (requiredType.isInstance(autowiringValue)) {
					//objectUtils.identityToString:可得到(obj的全类名+'@'+obj的hashCode的十六进制字符串),如果obj为null，返回空字符串
					//根据autowiringValue构建出唯一ID与autowiringValue绑定到result中
					result.put(ObjectUtils.identityToString(autowiringValue), autowiringValue);
					//跳槽循环
					break;
				}
			}
		}
		//常规匹配候选
		//遍历candidateNames
		for (String candidate : candidateNames) {
			//如果beanName与candidateName所对应的Bean对象不是同一个 且 candidate可以自动注入
			if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, descriptor)) {
				//添加一个条目在result中:一个bean实例(如果可用)或仅一个已解析的类型
				addCandidateEntry(result, candidate, descriptor, requiredType);
			}
		}
		//找不到候选时，就采用将回退模式尽可能的匹配到候选，一般情况下不会出现回退情况,除非代码非常糟糕
		//result为空
		if (result.isEmpty()) {
			//requiredType是否是数组/集合类型的标记
			boolean multiple = indicatesMultipleBeans(requiredType);
			// Consider fallback matches if the first pass failed to find anything...
			// 如果第一遍未找到任何内容，请考虑进行回退匹配
			// 在允许回退的情况下，候选Bean具有无法解析的泛型 || 候选Bean的Class对象是Properties类对象时，
			//   都允许成为 该描述符的可自动注入对象
			//获取desciptord的一个旨在用于回退匹配变体
			DependencyDescriptor fallbackDescriptor = descriptor.forFallbackMatch();
			//先尝试匹配候选bean名符合允许回退匹配的依赖描述符的自动依赖条件且(依赖类型不是集合/数组或者描述符指定限定符)的候选Bean对象
			//遍历candidateNames
			for (String candidate : candidateNames) {
				//getAutowireCandidateResolver()得到是QualifierAnnotationAutowireCandidateResolver实例,hasQualifier方法才有真正的限定符语义。
				//如果beanName与candidateName所对应的Bean对象不是同一个 且 candidate可以自动注入 且 (type不是数组/集合类型或者
				// desciptor有@Qualifier注解或qualifier标准修饰)
				if (!isSelfReference(beanName, candidate) && isAutowireCandidate(candidate, fallbackDescriptor) &&
						(!multiple || getAutowireCandidateResolver().hasQualifier(descriptor))) {
					//添加一个条目在result中:一个bean实例(如果可用)或仅一个已解析的类型
					addCandidateEntry(result, candidate, descriptor, requiredType);
				}
			}
			//匹配 除beanName符合描述符依赖类型不是数组/集合
			// 且 如果beanName与candidateName所对应的Bean对象不是同一个
			// 且 (descriptor不是集合依赖或者beanName与candidate不相同)
			// 且 候选bean名符合允许回退匹配的依赖描述符的自动依赖条件
			//如果result为空且requiredType不是数组/集合类型或者
			if (result.isEmpty() && !multiple) {
				// Consider self references as a final pass...
				// but in the case of a dependency collection, not the very same bean itself.
				// 将自我推荐视为最终通过
				// 但是对于依赖项集合，不是相同的bean本身
				//遍历candidateNames
				for (String candidate : candidateNames) {
					//如果beanName与candidateName所对应的Bean对象不是同一个 且 (descriptor不是MultiElementDescriptor实例(即集合依赖)或者
					// beanName不等于candidate) 且 candidate可以自动注入
					if (isSelfReference(beanName, candidate) &&
							(!(descriptor instanceof MultiElementDescriptor) || !beanName.equals(candidate)) &&
							isAutowireCandidate(candidate, fallbackDescriptor)) {
						///添加一个条目在result中:一个bean实例(如果可用)或仅一个已解析的类型
						addCandidateEntry(result, candidate, descriptor, requiredType);
					}
				}
			}
		}
		return result;
	}


	/**
	 * <p>
	 * 在候选映射中添加一个条目:一个bean实例(如果可用)或仅一个已解析的类型，以防止在选择主要
	 * 候选对象之前太早初始化bean:
	 *  <ol>
	 *   <li>如果desciprtor是MultiElementDescriptor的实例【集合类型依赖】:
	 *    <ol>
	 *     <li>获取candidateName的该工厂的Bean实例【变量 beanInstance】</li>
	 *     <li>如果beanInstance不是NullBean实例,将candidateName和其对应的实例绑定到candidates中</li>
	 *    </ol>
	 *   </li>
	 *   <li>如果beanName是在该BeanFactory的单例对象的高速缓存Map集合中 或者 (descriptor是SteamDependencyDesciptor实例【Stream类型依赖】且
	 *   该实例有排序标记):
	 *    <ol>
	 *     <li>获取candidateName的该工厂的Bean实例【变量 beanInstance】</li>
	 *     <li>如果beanInstance是NullBean实例,会将candidateName和null绑定到candidates中；否则将candidateName和其对应的实例绑定到candidates中</li>
	 *    </ol>
	 *   </li>
	 *   <li>否则(一般就是指candidatName所对应的bean不是单例):将candidateName和其对应的Class对象绑定到candidates中</li>
	 *  </ol>
	 * </p>
	 * Add an entry to the candidate map: a bean instance if available or just the resolved
	 * type, preventing early bean initialization ahead of primary candidate selection.
	 * <p>在候选映射中添加一个条目:一个bean实例(如果可用)或仅一个已解析的类型，以防止在选择主要
	 * 候选对象之前太早初始化bean</p>
	 */
	private void addCandidateEntry(Map<String, Object> candidates, String candidateName,
								   DependencyDescriptor descriptor, Class<?> requiredType) {
		//MultiElementDescriptor:具有嵌套元素的多元素声明的依赖描述符，表示集合/数组依赖
		//如果descriptor是MultiElementDescriptor的实例
		if (descriptor instanceof MultiElementDescriptor) {
			//获取candidateName的该工厂的Bean实例
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			//如果beanInstance不是NullBean实例
			if (!(beanInstance instanceof NullBean)) {
				//将candidateName和其对应的实例绑定到candidates中
				candidates.put(candidateName, beanInstance);
			}
		}
		//StreamDependencyDescriptor:用于访问多个元素的流依赖项描述符标记，即属性依赖是 stream类型且
		//如果beanName是在该BeanFactory的单例对象的高速缓存Map集合中 或者 (descriptor是SteamDependencyDesciptor实例 且 该实例有排序标记)
		else if (containsSingleton(candidateName) || (descriptor instanceof StreamDependencyDescriptor &&
				((StreamDependencyDescriptor) descriptor).isOrdered())) {
			//获取candidateName的该工厂的Bean实例
			Object beanInstance = descriptor.resolveCandidate(candidateName, requiredType, this);
			//如果beanInstance是NullBean实例,会将candidateName和null绑定到candidates中；否则将candidateName和其对应的实例绑定到candidates中
			candidates.put(candidateName, (beanInstance instanceof NullBean ? null : beanInstance));
		}
		//candidateName所对应的bean不是单例
		else {
			//将candidateName和其对应的Class对象绑定到candidates中
			candidates.put(candidateName, getType(candidateName));
		}
	}


	/**
	 * <p>确定candidates中可以自动注入的最佳候选Bean名称:
	 *  <ol>
	 *   <li>获取descriptor的依赖类型【变量 requiredType】</li>
	 *   <li>【<b>在candidates中确定primary候选Bean名,即被Primary注解修饰或者在BeanDefinition中
	 *   显示声明的bean名</b>】:
	 *    <ol>
	 *     <li>在candidates中确定primary候选Bean名【变量 primaryCandidate】</li>
	 *     <li>如果primary候选Bean名不为null，返回primary候选bean名</li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>确定candidates中具有Priority注解最高优先级的候选Bean名,即被Priority注解修饰的最高
	 *   优先级值的bean名</b>】:
	 *    <ol>
	 *     <li>确定candidates中具有Priority注解最高优先级的候选Bean名【变量 priorityCandidate】</li>
	 *     <li>如果primary候选Bean名不为null,返回primary候选bean名</li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>尝试匹配与descriptor包装的参数/字段的名称相同的 或者 {@link #resolvableDependencies}中存在的
	 *   候选Bean名</b>】:
	 *    <ol>
	 *     <li>遍历候选Bean对象Map:
	 *      <ol>
	 *       <li>获取元素的候选bean名【变量 candidateName】</li>
	 *       <li>获取元素的候选bena对象【变量 beanInstance】</li>
	 *       <li>如果(beanInstance不为null 且 存放着手动显示注册的依赖项类型-相应的自动装配值的缓存 中包含该bean对象)
	 *       或者 descriptor包装的参数/字段的名称与candidateName或此candidateName的BeanDefinition中存储的别名匹配,
	 *       返回该候选别名</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>返回null,表示没找到可以自动注入的最佳候选Bean名称</li>
	 *  </ol>
	 * </p>
	 * Determine the autowire candidate in the given set of beans.
	 * <p>在给定的bean组中确定可以自动注入的候选Bean对象</p>
	 * <p>Looks for {@code @Primary} and {@code @Priority} (in that order).
	 * <p>查找{@code @Primary}和{@code @Prioity}(按此顺序)</p>
	 *
	 * @param candidates a Map of candidate names and candidate instances
	 *                   that match the required type, as returned by {@link #findAutowireCandidates}
	 *                   -- 由{@link #findAutowireCandidates}返回的匹配所需类型的候选名称和候选实例的Map
	 * @param descriptor the target dependency to match against
	 *                   -- 要匹配的目标依赖项
	 * @return the name of the autowire candidate, or {@code null} if none found
	 * -- 自动接线候选者的名称，即可以自动注入的最佳候选Bean名称；如果找不到，则为null
	 */
	@Nullable
	protected String determineAutowireCandidate(Map<String, Object> candidates, DependencyDescriptor descriptor) {
		//获取descriptor的依赖类型
		Class<?> requiredType = descriptor.getDependencyType();
		//在candidates中确定primary候选Bean名
		String primaryCandidate = determinePrimaryCandidate(candidates, requiredType);
		//如果primary候选Bean名不为null
		if (primaryCandidate != null) {
			//返回primary候选bean名
			return primaryCandidate;
		}
		//确定candidates中具有Priority注解最高优先级的候选Bean名
		String priorityCandidate = determineHighestPriorityCandidate(candidates, requiredType);
		//如果primary候选Bean名不为null
		if (priorityCandidate != null) {
			//返回primary候选bean名
			return priorityCandidate;
		}
		// Fallback 回退
		//遍历候选Bean对象Map
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			//获取元素的候选bean名
			String candidateName = entry.getKey();
			//获取元素的候选bena对象
			Object beanInstance = entry.getValue();
			// 如果(beanInstance不为null 且 存放着手动显示注册的依赖项类型-相应的自动装配值的缓存 中包含该bean对象)
			// 或者 descriptor包装的参数/字段的名称与candidateName或此candidateName的BeanDefinition中存储的别名匹配
			if ((beanInstance != null && this.resolvableDependencies.containsValue(beanInstance)) ||
					matchesBeanName(candidateName, descriptor.getDependencyName())) {
				//返回该候选别名
				return candidateName;
			}
		}
		//返回null,表示没找到可以自动注入的最佳候选Bean名称
		return null;
	}

	/**
	 * <p>在candidates中确定primary候选Bean名:
	 *  <ol>
	 *   <li>定义用于存储primary bean名的变量【变量 primaryBeanName】</li>
	 *   <li>遍历候选bean对象Map:
	 *    <ol>
	 *     <li>获取元素的候选bean名【变量 candidateBeanName】</li>
	 *     <li>获取元素的候选bean对象【变量 beanInstance】</li>
	 *     <li>如果candidateBeanName的beanDefinition已标记为primary bean:
	 *      <ol>
	 *       <li>如果primaryBeanName不为null
	 *        <ol>
	 *         <li>本地工厂中BeanDefinition对象映射【beanDefinitionMap】中是否存在
	 *         candidateBeanName该键名，将结果赋值给【变量 candidateLocal】</li>
	 *         <li>本地工厂中BeanDefinition对象映射【beanDefinitionMap】中是否存在
	 *         primaryBeanName该键名，将结果赋值给【变量 primaryLocal】</li>
	 *         <li>如果candiateLocalh和primaryLocal都为true,表示candidateBeanName和primaryBeanName
	 *         都存在于本地工厂中BeanDefinition对象映射【beanDefinitionMap】中，就抛出没有唯一BeanDefinition异常：
	 *         在候选Bean对象Map中发现一个以上的"primary" bean
	 *         </li>
	 *         <li>如果只是candidateLocal为true，表示只是candidateBeanName存在于本地工厂中BeanDefinition对
	 *         象映射【beanDefinitionMap】中,就让primaryBeanName引用candidateBeanName</li>
	 *        </ol>
	 *       </li>
	 *       <li>否则，让primaryBeanName直接引用candidateBeanName</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>返回primary bean名【primaryBeanName】</li>
	 *  </ol>
	 * </p>
	 * Determine the primary candidate in the given set of beans.
	 * <p>在给定的bean集中确定primary候选对象</p>
	 *
	 * @param candidates   a Map of candidate names and candidate instances
	 *                     (or candidate classes if not created yet) that match the required type
	 *                     -- 匹配所需类型的候选名称和候选实例(或候选类，如果尚未创建,即PROTOTYPE级别的Bean) 的映射
	 * @param requiredType the target dependency type to match against
	 *                     -- 要匹配的目标依赖类型
	 * @return the name of the primary candidate, or {@code null} if none found
	 * -- primary候选Bean名，如果找不到则为null
	 * @see #isPrimary(String, Object)
	 */
	@Nullable
	protected String determinePrimaryCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		//定义用于存储primary bean名的变量
		String primaryBeanName = null;
		//遍历候选bean对象Map
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			//获取元素的候选bean名
			String candidateBeanName = entry.getKey();
			//获取元素的候选bean对象
			Object beanInstance = entry.getValue();
			//如果candidateBeanName的beanDefinition已标记为primary bean
			if (isPrimary(candidateBeanName, beanInstance)) {
				//如果primaryBeanName不为null
				if (primaryBeanName != null) {
					//本地工厂中BeanDefinition对象映射【beanDefinitionMap】中是否存在candidateBeanName该键名，将结果
					// 赋值给candidateLocal
					boolean candidateLocal = containsBeanDefinition(candidateBeanName);
					//本地工厂中BeanDefinition对象映射【beanDefinitionMap】中是否存在primaryBeanName该键名，将结果
					// 赋值给primaryLocal
					boolean primaryLocal = containsBeanDefinition(primaryBeanName);
					//如果candiateLocalh和primaryLocal都为true,表示candidateBeanName和primaryBeanName都存在于本地
					// 	工厂中BeanDefinition对象映射【beanDefinitionMap】中
					if (candidateLocal && primaryLocal) {
						//抛出没有唯一BeanDefinition异常：在候选Bean对象Map中发现一个以上的"primary" bean
						throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
								"more than one 'primary' bean found among candidates: " + candidates.keySet());
					}
					//如果只是candidateLocal为true，表示只是candidateBeanName存在于本地工厂中BeanDefinition对
					// 象映射【beanDefinitionMap】中
					else if (candidateLocal) {
						//让primaryBeanName引用candidateBeanName
						primaryBeanName = candidateBeanName;
					}
				} else {
					//让primaryBeanName直接引用candidateBeanName
					primaryBeanName = candidateBeanName;
				}
			}
		}
		//返回primary bean名【primaryBeanName】
		return primaryBeanName;
	}

	/**
	 * <p>确定candidates中具有Priority注解最高优先级的候选Bean名：
	 *  <ol>
	 *   <li>定义用于保存最高优先级bean名的变量【变量 highestPriorityBeanName】</li>
	 *   <li>定义用于保存最搞优先级值的变量【变量 highestPriority】</li>
	 *   <li>遍历候选bean对象Map:
	 *    <ol>
	 *     <li>获取元素的候选bean名【变量 candidateBeanName】</li>
	 *     <li>获取元素的候选bean对象【变量 beanInstance】</li>
	 *     <li>如果beanInstance不为null
	 *      <ol>
	 *       <li>获取Priority注解为beanInstance分配的优先级值【变量 candidatePriority】</li>
	 *       <li>如果候选Bean对象的优先级值不为null:
	 *        <ol>
	 *         <li>如果最高优先级bean名不为null:
	 *          <ol>
	 *           <li>如果候选优先级值与最高优先级级值相同,抛出没有唯一BeanDefinition异常【{@link NoUniqueBeanDefinitionException}】</li>
	 *           <li>如果后续优先级值小于最高优先级值：
	 *            <ol>
	 *             <li>让highestPriorityBeanName引用candidateBeanName</li>
	 *             <li>让highestPriority引用candidatePriority</li>
	 *            </ol>
	 *           </li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li>否则：
	 *        <ol>
	 *         <li>让highestPriorityBeanName引用candidateBeanName</li>
	 *         <li>让highestPriority引用candidatePriority</li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>返回最高优先级bean名【highestPriorityBeanName】</li>
	 *  </ol>
	 * </p>
	 * Determine the candidate with the highest priority in the given set of beans.
	 * <p>确定给定bean组中具有最高优先级的候选对象</p>
	 * <p>Based on {@code @javax.annotation.Priority}. As defined by the related
	 * {@link org.springframework.core.Ordered} interface, the lowest value has
	 * the highest priority.
	 * <p>基于{@code javax.annotation.Priority}.如相关org.springframwwork.core.Ordered接口
	 * 定义，最低优先级值具有最高优先级</p>
	 *
	 * @param candidates   a Map of candidate names and candidate instances
	 *                     (or candidate classes if not created yet) that match the required type
	 *                     -- 匹配所需类型的候选名称和候选实例(后候选类，如果尚未创建,即PROTOTYPE级别的Bean)的映射
	 * @param requiredType the target dependency type to match against
	 *                     -- 要匹配的目标依赖项类型
	 * @return the name of the candidate with the highest priority,
	 * or {@code null} if none found
	 * -- 优先级最高候选Bean名；如果找不到，则为null
	 * @see #getPriority(Object)
	 */
	@Nullable
	protected String determineHighestPriorityCandidate(Map<String, Object> candidates, Class<?> requiredType) {
		//定义用于保存最高优先级bean名的变量
		String highestPriorityBeanName = null;
		//定义用于保存最搞优先级值的变量
		Integer highestPriority = null;
		//遍历候选Bean对象Map
		for (Map.Entry<String, Object> entry : candidates.entrySet()) {
			//获取元素的候选bean名
			String candidateBeanName = entry.getKey();
			//获取元素的候选bean对象
			Object beanInstance = entry.getValue();
			//如果beanInstance不为null
			if (beanInstance != null) {
				//获取Priority注解为beanInstance分配的优先级值
				Integer candidatePriority = getPriority(beanInstance);
				//如果候选Bean对象的优先级值不为null
				if (candidatePriority != null) {
					//如果最高优先级bean名不为null
					if (highestPriorityBeanName != null) {
						//如果候选优先级值与最高优先级级值相同
						if (candidatePriority.equals(highestPriority)) {
							//抛出没有唯一BeanDefinition异常：找到具有相同优先级的多个bean('highestPriority')再候选Bean对象中
							throw new NoUniqueBeanDefinitionException(requiredType, candidates.size(),
									"Multiple beans found with the same priority ('" + highestPriority +
											"') among candidates: " + candidates.keySet());
						}
						//如果后续优先级值小于最高优先级值
						else if (candidatePriority < highestPriority) {
							//让最高优先级bean名引用候选bean名
							highestPriorityBeanName = candidateBeanName;
							//让最高优先级值引用候选优先级值
							highestPriority = candidatePriority;
						}
					} else {
						//让最高优先级bean名引用候选bean名
						highestPriorityBeanName = candidateBeanName;
						//让最高优先级值引用候选优先级值
						highestPriority = candidatePriority;
					}
				}
			}
		}
		//返回最高优先级bean名
		return highestPriorityBeanName;
	}

	/**
	 * Return whether the bean definition for the given bean name has been
	 * marked as a primary bean.
	 * <p>返回给定bean名的beanDefinition是否已标记为primary bean</p>
	 * <ol>
	 *  <li>去除beanName开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】【变量 transformedBeanName】</li>
	 *  <li>如果Beand定义对象映射【beanDefinitionMap】中存在beanName该键名:
	 *   <ol>
	 *    <li>获取beanName合并后的本地RootBeanDefintiond,以判断是否为自动装配的 primary 候选对
	 *    象并将结果返回出去</li>
	 *   </ol>
	 *  </li>
	 *  <li>获取父工厂【变量 parent】</li>
	 *  <li>如果父工厂为DefaultListableBeanFactory,则使用父工厂递归该方法进行判断transformedBeanName
	 *  的beanDefinition是否已标记为primary bean并将结果返回出去。</li>
	 * </ol>
	 *
	 * @param beanName     the name of the bean -- bean名
	 * @param beanInstance the corresponding bean instance (can be null)
	 *                     -- 相应的bean实例(可以为null)
	 * @return whether the given bean qualifies as primary
	 * -- 给定的bean是否符合primary
	 */
	protected boolean isPrimary(String beanName, Object beanInstance) {
		//去除beanName开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】
		String transformedBeanName = transformedBeanName(beanName);
		//如果Beand定义对象映射【beanDefinitionMap】中存在beanName该键名
		if (containsBeanDefinition(transformedBeanName)) {
			//获取beanName合并后的本地RootBeanDefintiond,以判断是否为自动装配的 primary 候选对象
			// 并将结果返回出去
			return getMergedLocalBeanDefinition(transformedBeanName).isPrimary();
		}
		//获取父工厂
		BeanFactory parent = getParentBeanFactory();
		//如果父工厂为DefaultListableBeanFactory,则使用父工厂递归该方法进行判断transformedBeanName
		// 的beanDefinition是否已标记为primary bean并将结果返回出去。
		return (parent instanceof DefaultListableBeanFactory &&
				((DefaultListableBeanFactory) parent).isPrimary(transformedBeanName, beanInstance));
	}


	/**
	 * Return the priority assigned for the given bean instance by
	 * the {@code javax.annotation.Priority} annotation.
	 * <p>返回{@code javax.annotation.Priority}注解为给定bean实例分配的优先级</p>
	 * <p>The default implementation delegates to the specified
	 * {@link #setDependencyComparator dependency comparator}, checking its
	 * {@link OrderComparator#getPriority method} if it is an extension of
	 * Spring's common {@link OrderComparator} - typically, an
	 * {@link org.springframework.core.annotation.AnnotationAwareOrderComparator}.
	 * If no such comparator is present, this implementation returns {@code null}.
	 * <p>默认实现委托给指定的依赖比较器，检查其方法是否是Spring通常OrderComparator的扩展
	 * -通常是org.springframework.core.annotation.AnnotationAwareOrderComparator.
	 * 如果不存在这样的比较其，则此实现返回null</p>
	 *
	 * @param beanInstance the bean instance to check (can be {@code null})
	 *                     -- 要检查的bean实例(可以为{@code null})
	 * @return the priority assigned to that bean or {@code null} if none is set
	 * 分配给该bean的优先级;如果未设置,则为null
	 */
	@Nullable
	protected Integer getPriority(Object beanInstance) {
		//获取此BeanFactory的依赖关系比较器
		Comparator<Object> comparator = getDependencyComparator();
		//如果比较器是OrderComparator的实例
		if (comparator instanceof OrderComparator) {
			//返回beanInstance的优先级值
			return ((OrderComparator) comparator).getPriority(beanInstance);
		}
		//返回null，表示获取不到
		return null;
	}

	/**
	 * Determine whether the given candidate name matches the bean name or the aliases
	 * stored in this bean definition.
	 * <p>确定给定的候选Bean名称是否与Bean名称或此bean定义中存储的别名匹配</p>
	 *
	 * @param beanName      要匹配的bean名
	 * @param candidateName 候选bean名
	 */
	protected boolean matchesBeanName(String beanName, @Nullable String candidateName) {
		//如果candidateName不为null 且 (候选bean名与beanName相同或者candidateName在beanName的所有别名中存在)
		return (candidateName != null &&
				(candidateName.equals(beanName) || ObjectUtils.containsElement(getAliases(beanName), candidateName)));
	}


	/**
	 * <p>可以理解为beanName与candidateName所对应的Bean对象是不是同一个</p>
	 * <p>自引用：beanName和candidateName是否都是指向同一个Bean对象，至少beanName所指bean对象是candidateName的合并后
	 * RootBeanDefinition对象里的FactoryBean对象</p>
	 * Determine whether the given beanName/candidateName pair indicates a self reference,
	 * i.e. whether the candidate points back to the original bean or to a factory method
	 * on the original bean.
	 * <p>确定给定beanName/candidateName Pair 是否表示自引用,即候选对象是指向原始bean
	 * 或者指向原始bean的工厂方法</p>
	 */
	private boolean isSelfReference(@Nullable String beanName, @Nullable String candidateName) {
		//如果beanName和candidateName都不会null
		// 且 beanName与candidateName相等 或者 (该工厂有candidateName的BeanDefinition对象 且 candidateName的合并后BeanDefinition对象的FactoryBean名与beanName相等)
		return (beanName != null && candidateName != null &&
				(beanName.equals(candidateName) || (containsBeanDefinition(candidateName) &&
						beanName.equals(getMergedLocalBeanDefinition(candidateName).getFactoryBeanName()))));
	}

	/**
	 * Raise a NoSuchBeanDefinitionException or BeanNotOfRequiredTypeException
	 * for an unresolvable dependency.
	 * <p>抛出NoSuchBeanDefinitionException或BeanNotOfRequiredTypeException以解决不可
	 * 解决的依赖关系</p>
	 *
	 * @param type           descriptor的依赖类型
	 * @param resolvableType descriptor用包装的参数/字段构建出来的ResolvableType对象
	 * @param descriptor     descriptor
	 */
	private void raiseNoMatchingBeanFound(
			Class<?> type, ResolvableType resolvableType, DependencyDescriptor descriptor) throws BeansException {

		//检查Bean是否属于Type类型，如果不是抛出BeanNotOfRequiredTypeException
		checkBeanNotOfRequiredType(type, descriptor);

		//抛出没有此类Bean定义异常：至少要有1个可以作为自动装配候选bean对象，依赖注解:descriptor中与包装的field 或者方法/构
		// 	造函数参数关联的注解
		throw new NoSuchBeanDefinitionException(resolvableType,
				"expected at least 1 bean which qualifies as autowire candidate. " +
						"Dependency annotations: " + ObjectUtils.nullSafeToString(descriptor.getAnnotations()));
	}

	/**
	 * 检查Bean是否属于Type类型，如果不是抛出BeanNotOfRequiredTypeException
	 * <ol>
	 *  <li>遍历Bean定义名称列表【{@link #beanDefinitionNames}】【变量 beanName】:
	 *   <ol>
	 *    <li>获取beanName对应的合并后BeanDefinition【变量 mbd】</li>
	 *    <li>获取mbd的目标类型【变量 targetType】</li>
	 *    <li>如果目标类型不为null，且 目标类型属于descriptor的依赖类型 且 mbd可以自动注入到descriptor
	 *    所包装的field/methodParam中:
	 *     <ol>
	 *      <li>获取以beanName注册的(原始)单例Bean对象【变量 beanInstance】</li>
	 *      <li>如果beanInstance不为null且不是NUllBean.class，则获取beanInstance的Class对象；否则预测
	 *      beanName,mbd所提供的信息的最终bean类型</li>
	 *      <li>如果beanType不为null且beanType不是descriptord的依赖类型,抛出：
	 *      Bean不是必需的类型异常【{@link BeanNotOfRequiredTypeException}】</li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 *  </li>
	 *  <li>获取父工厂</li>
	 *  <li>如果父工厂是DefaultListableBeanFactory的实例，递归调用父工厂的该方法进行检查</li>
	 * </ol>
	 * Raise a BeanNotOfRequiredTypeException for an unresolvable dependency, if applicable,
	 * i.e. if the target type of the bean would match but an exposed proxy doesn't.
	 * <p>抛出BeanNotOfRequiredTypeException以解决不可解决的依赖关系(如果适用),即，如果Bean的
	 * 目标类型匹配，但公开的代理不匹配。</p>
	 *
	 * @param type       descriptor的依赖类型
	 * @param descriptor descriptor
	 */
	private void checkBeanNotOfRequiredType(Class<?> type, DependencyDescriptor descriptor) {
		//遍历Bean定义名称列表
		for (String beanName : this.beanDefinitionNames) {
			//获取beanName对应的合并后BeanDefinition
			RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
			//获取mbd的目标类型
			Class<?> targetType = mbd.getTargetType();
			//如果目标类型不为null，且 目标类型属于descriptor的依赖类型 且 mbd可以自动注入到descriptor所包装的field/methodParam中
			if (targetType != null && type.isAssignableFrom(targetType) &&
					isAutowireCandidate(beanName, mbd, descriptor, getAutowireCandidateResolver())) {
				// Probably a proxy interfering with target type match -> throw meaningful exception.
				// 可能是干扰目标类型匹配的代理->抛出有意义的异常
				//获取以beanName注册的(原始)单例Bean对象
				Object beanInstance = getSingleton(beanName, false);
				//如果beanInstance不为null且不是NUllBean.class，则获取beanInstance的Class对象；否则预测beanName,mbd所提供的信息的最终bean类型
				Class<?> beanType = (beanInstance != null && beanInstance.getClass() != NullBean.class ?
						beanInstance.getClass() : predictBeanType(beanName, mbd));
				//如果beanType不为null且beanType不是descriptord的依赖类型
				if (beanType != null && !type.isAssignableFrom(beanType)) {
					//抛出：Bean不是必需的类型异常
					throw new BeanNotOfRequiredTypeException(beanName, type, beanType);
				}
			}
		}

		//如果父工厂是DefaultListableBeanFactory的实例，
		if (getParentBeanFactory() instanceof DefaultListableBeanFactory parent) {
			//递归调用父工厂的该方法进行检查
			parent.checkBeanNotOfRequiredType(type, descriptor);
		}
	}


	/**
	 * <p>创建Optional类型的符合descriptor要求的候选Bean对象:
	 *  <ol>
	 *   <li>新建一个NestedDependencyDescriptor实例，,该实例不要求一定要得到候选Bean对象，且可根据arg构建候选Bean对象且
	 *   	可根据arg构建候选Bean对象(仅在Bean是{@link #SCOPE_PROTOTYPE}时)。【变量 descriptorToUse】</li>
	 *   <li>解析出与descriptor所包装的对象匹配的后续Bean对象【变量 result】</li>
	 *   <li>如果result是Optional的实例,就将其强转为Optional后返回出去；否则将result包装到Optional对象中再返回出去</li>
	 *  </ol>
	 * </p>
	 * Create an {@link Optional} wrapper for the specified dependency.
	 * <p>为指定的依赖关系创建一个{@link Optional}包装器</p>
	 *
	 * @param descriptor 依赖项的描述符(字段/方法/构造函数)
	 * @param beanName   要依赖的Bean名,即需要Field/MethodParamter所对应的bean对象来构建的Bean对象的Bean名
	 * @param args       创建候选Bean对象所需的构造函数参数(仅在Bean是{@link #SCOPE_PROTOTYPE}时)
	 * @return Optional类型的符合descriptor要求的候选Bean对象, 不会为null
	 */
	private Optional<?> createOptionalDependency(
			DependencyDescriptor descriptor, @Nullable String beanName, final Object... args) {
		//NestedDependencyDescriptor：嵌套元素的依赖项描述符标记，一般表示Optional类型依赖
		//新建一个NestedDependencyDescriptor实例,该实例不要求一定要得到候选Bean对象，且可根据arg构建候选Bean对象(当Bean是{@link #SCOPE_PROTOTYPE}时)
		DependencyDescriptor descriptorToUse = new NestedDependencyDescriptor(descriptor) {
			/**
			 * 不要求一定要得到候选Bean对象
			 */
			@Override
			public boolean isRequired() {
				return false;
			}

			/**
			 * 将指定的Bean名称解析为给定工厂的Bean实例，作为对此依赖项的匹配算法的候选结果：
			 * <p>重写该方法，使得该方法可以引用args来获取beanName的bean对象</p>
			 * @param beanName the bean name, as a candidate result for this dependency
			 *                 -- bean名，作为此依赖项的候选结果
			 * @param requiredType the expected type of the bean (as an assertion)
			 *                     -- bean的预期类型（作为断言）
			 * @param beanFactory the associated factory -- 相关工厂
			 * @return Bean名所对应的Bean对象
			 */
			@Override
			public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
				//如果args不是空数组，就调用beanFactory.getBean(beanName, args)方法，即引用args来获取beanName的bean对象
				// 否则 调用父级默认实现；默认实现调用BeanFactory.getBean(beanName).
				return (!ObjectUtils.isEmpty(args) ? beanFactory.getBean(beanName, args) :
						super.resolveCandidate(beanName, requiredType, beanFactory));
			}
		};
		//解析出与descriptor所包装的对象匹配的后续Bean对象
		Object result = doResolveDependency(descriptorToUse, beanName, null, null);
		//如果result是Optional的实例,就将其强转为Optional后返回出去；否则将result包装到Optional对象中再返回出去
		return (result instanceof Optional ? (Optional<?>) result : Optional.ofNullable(result));
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(ObjectUtils.identityToString(this));
		sb.append(": defining beans [");
		sb.append(StringUtils.collectionToCommaDelimitedString(this.beanDefinitionNames));
		sb.append("]; ");
		BeanFactory parent = getParentBeanFactory();
		if (parent == null) {
			sb.append("root of factory hierarchy");
		} else {
			sb.append("parent: ").append(ObjectUtils.identityToString(parent));
		}
		return sb.toString();
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	@Serial
	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		throw new NotSerializableException("DefaultListableBeanFactory itself is not deserializable - " +
				"just a SerializedBeanFactoryReference is");
	}

	@Serial
	protected Object writeReplace() throws ObjectStreamException {
		if (this.serializationId != null) {
			return new SerializedBeanFactoryReference(this.serializationId);
		} else {
			throw new NotSerializableException("DefaultListableBeanFactory has no serialization id");
		}
	}


	/**
	 * Minimal id reference to the factory.
	 * Resolved to the actual factory instance on deserialization.
	 */
	private static class SerializedBeanFactoryReference implements Serializable {

		private final String id;

		public SerializedBeanFactoryReference(String id) {
			this.id = id;
		}

		private Object readResolve() {
			Reference<?> ref = serializableFactories.get(this.id);
			if (ref != null) {
				Object result = ref.get();
				if (result != null) {
					return result;
				}
			}
			// Lenient fallback: dummy factory in case of original factory not found...
			DefaultListableBeanFactory dummyFactory = new DefaultListableBeanFactory();
			dummyFactory.serializationId = this.id;
			return dummyFactory;
		}
	}

	/**
	 * A dependency descriptor marker for nested elements.
	 * <p>嵌套元素的依赖项描述符标记，一般表示{@link Optional}类型依赖</p>
	 */
	private static class NestedDependencyDescriptor extends DependencyDescriptor {

		/**
		 * 新建一个NestedDependencyDescriptor实例
		 *
		 * @param original 从其创建副本的原始描述符
		 */
		public NestedDependencyDescriptor(DependencyDescriptor original) {
			//拷贝 original 的属性
			super(original);
			//增加此描述符的嵌套级别
			increaseNestingLevel();
		}
	}


	/**
	 * A dependency descriptor for a multi-element declaration with nested elements.
	 * <p>具有嵌套元素的多元素声明的依赖描述符，表示集合/数组依赖</p>
	 */
	private static class MultiElementDescriptor extends NestedDependencyDescriptor {

		/**
		 * 新建一个StreamDependencyDescriptor实例
		 *
		 * @param original 从其创建副本的原始描述符
		 */
		public MultiElementDescriptor(DependencyDescriptor original) {
			//拷贝 originald 的属性
			super(original);
		}
	}

	/**
	 * A dependency descriptor marker for stream access to multiple elements.
	 * <p>用于访问多个元素的流依赖项描述符标记，一般表示stream类型依赖</p>
	 */
	private static class StreamDependencyDescriptor extends DependencyDescriptor {

		/**
		 * 是否需要排序标记
		 */
		private final boolean ordered;

		/**
		 * 新建一个StreamDependencyDescriptor实例
		 *
		 * @param original 从其创建副本的原始描述符
		 * @param ordered  是否需要排序标记
		 */
		public StreamDependencyDescriptor(DependencyDescriptor original, boolean ordered) {
			//拷贝originald的属性
			super(original);
			this.ordered = ordered;
		}

		/**
		 * 是否需要排序
		 */
		public boolean isOrdered() {
			return this.ordered;
		}
	}

	/**
	 * Bean对象专用ObjectProider,提供可序列化
	 *
	 * @param <T>
	 */
	private interface BeanObjectProvider<T> extends ObjectProvider<T>, Serializable {
	}

	/**
	 * <p>依赖对象提供者,用于延迟解析依赖项</p>
	 * Serializable ObjectFactory/ObjectProvider for lazy resolution of a dependency.
	 * <p>可序列化的ObjectFactory/ObjectProvider，用于延迟解析依赖项</p>
	 */
	private class DependencyObjectProvider implements BeanObjectProvider<Object> {

		/**
		 * 依赖项的描述符(字段/方法/构造函数)
		 */
		private final DependencyDescriptor descriptor;

		/**
		 * descriptor的依赖类型是否是Optional类标记
		 */
		private final boolean optional;

		/**
		 * 要依赖的Bean名,即需要Field/MethodParamter所对应的bean对象来构建的Bean对象的Bean名
		 */
		@Nullable
		private final String beanName;

		/**
		 * 新建一个DependencyObjectProvider的实例
		 *
		 * @param descriptor 依赖项的描述符(字段/方法/构造函数)
		 * @param beanName   要依赖的Bean名,即需要Field/MethodParamter所对应的bean对象来构建的Bean对象的Bean名
		 */
		public DependencyObjectProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			this.descriptor = new NestedDependencyDescriptor(descriptor);
			this.optional = (this.descriptor.getDependencyType() == Optional.class);
			this.beanName = beanName;
		}

		/**
		 * 获取与descriptor所包装的对象匹配的候选Bean对象:
		 * <ol>
		 *  <li>如果descriptor的依赖类型是Optional类，创建Optional类型的符合descriptor要求的候选Bean对象并返回出去</li>
		 *  <li>否则:
		 *   <ol>
		 *    <li>解析出与descriptor所包装的对象匹配的候选Bean对象【变量 result】</li>
		 *    <li>如果result为null,抛出 没有此类的BeanDefinition异常</li>
		 *    <li>返回该候选Bean对象【result】</li>
		 *   </ol>
		 *  </li>
		 * </ol>
		 *
		 * @return 结果实例
		 * @throws BeansException 如果出现创建错误,比如获取不到候选Bean对象
		 */
		@Override
		public Object getObject() throws BeansException {
			//如果descriptor的依赖类型是Optional类
			if (this.optional) {
				//创建Optional类型的符合descriptor要求的候选Bean对象并返回出去
				return createOptionalDependency(this.descriptor, this.beanName);
			} else {
				//解析出与descriptor所包装的对象匹配的候选Bean对象
				Object result = doResolveDependency(this.descriptor, this.beanName, null, null);
				//如果result为null
				if (result == null) {
					//抛出 没有此类的BeanDefinition异常
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				//返回该候选Bean对象
				return result;
			}
		}

		/**
		 * 获取与descriptor所包装的对象匹配的候选Bean对象
		 * <ol>
		 *  <li>如果descriptor的依赖类型是Optional类,创建Optional类型的符合descriptor要求的候选Bean对象并返回出去，
		 *  在创建候选Bean对象时会引用args</li>
		 *  <li>否则:
		 *   <ol>
		 *    <li>新建一个descriptor副本实例，重写descriptor#resolveCandidate方法，使其可以支持引用args
		 *    创建候选bean对象【变量 descriptorToUser】</li>
		 *    <li>解析出与descriptor副本所包装的对象匹配的候选Bean对象</li>
		 *    <li>如果result为null,抛出 没有此类的BeanDefinition异常</li>
		 *    <li>返回该候选Bean对象</li>
		 *   </ol>
		 *  </li>
		 * </ol>
		 *
		 * @param args arguments to use when creating a corresponding instance
		 *             -- 创建相应实例时要使用的参数，一般是该实例的构造函数
		 * @return 结果实例
		 * @throws BeansException 如果出现创建错误,比如获取不到候选Bean对象
		 */
		@Override
		public Object getObject(final Object... args) throws BeansException {
			//如果descriptor的依赖类型是Optional类
			if (this.optional) {
				//创建Optional类型的符合descriptor要求的候选Bean对象并返回出去，在创建候选Bean对象时会引用args
				return createOptionalDependency(this.descriptor, this.beanName, args);
			} else {
				//新建一个descriptor副本实例，重写descriptor#resolveCandidate方法，使其可以支持引用args创建候选bean对象
				DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
					@Override
					public Object resolveCandidate(String beanName, Class<?> requiredType, BeanFactory beanFactory) {
						return beanFactory.getBean(beanName, args);
					}
				};
				//解析出与descriptor副本所包装的对象匹配的候选Bean对象
				Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
				//如果result为null
				if (result == null) {
					//抛出 没有此类的BeanDefinition异常
					throw new NoSuchBeanDefinitionException(this.descriptor.getResolvableType());
				}
				//返回该候选Bean对象
				return result;
			}
		}

		/**
		 * 如果与descriptor所包装的对象匹配的候选Bean对象已注册到容器中, 返回bean实例,
		 * 否则返回 null
		 * <ol>
		 *  <li>如果descriptor的依赖类型是Optional类,创建Optional类型的符合descriptor要求的候选Bean对象
		 *  并返回出去</li>
		 *  <li>新建一个descriptor副本实例,该实例不要求一定要得到候选Bean对象</li>
		 *  <li>解析出与descriptor副本所包装的对象匹配的候选Bean对象并返回出去</li>
		 * </ol>
		 *
		 * @return 与descriptor所包装的对象匹配的候选Bean对象, 获取不到时返回null
		 * @throws BeansException 如果出现创建错误
		 */
		@Override
		@Nullable
		public Object getIfAvailable() throws BeansException {
			//如果descriptor的依赖类型是Optional类
			if (this.optional) {
				//创建Optional类型的符合descriptor要求的候选Bean对象并返回出去
				return createOptionalDependency(this.descriptor, this.beanName);
			} else {
				//新建一个descriptor副本实例,该实例不要求一定要得到候选Bean对象
				DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
					/**
					 * 不要求一定要得到候选Bean对象
					 */
					@Override
					public boolean isRequired() {
						return false;
					}
				};
				//解析出与descriptor副本所包装的对象匹配的候选Bean对象
				return doResolveDependency(descriptorToUse, this.beanName, null, null);
			}
		}

		/**
		 * 如果与descriptor所包装的对象匹配的候选Bean对象不可用或不唯一（没有指定primary）
		 * 则返回null。否则，返回对象
		 * <ol>
		 *  <li>新建一个descriptor副本实例,该实例不要求一定要得到候选Bean对象,在让descriptor尝
		 *  试选择其中一个实例时,返回null</li>
		 *  <li>如果descriptor的依赖类型是Optional类,创建Optional类型的符合descriptor要求
		 *  的候选Bean对象并返回出去</li>
		 *  <li>否则,解析出与descriptor副本所包装的对象匹配的候选Bean对象并返回出去</li>
		 * </ol>
		 *
		 * @return 与descriptor所包装的对象匹配的候选Bean对象, 获取不到或不唯一时返回null
		 * @throws BeansException 如果出现创建错误
		 */
		@Override
		@Nullable
		public Object getIfUnique() throws BeansException {
			//新建一个descriptor副本实例,该实例不要求一定要得到候选Bean对象,在让descriptor尝
			// 试选择其中一个实例时,返回null
			DependencyDescriptor descriptorToUse = new DependencyDescriptor(this.descriptor) {
				/**
				 * 不要求一定要得到候选Bean对象
				 */
				@Override
				public boolean isRequired() {
					return false;
				}

				/**
				 * 在让descriptor尝试选择其中一个实例时,返回null
				 * @param type the requested bean type
				 *             -- 请求的bean类型
				 * @param matchingBeans a map of bean names and corresponding bean
				 * instances which have been pre-selected for the given type
				 * (qualifiers etc already applied)
				 *     -- 已为给定类型预先选择的Bean名称和对应Bean实例的映射(已应用限定符等)
				 */
				@Override
				@Nullable
				public Object resolveNotUnique(ResolvableType type, Map<String, Object> matchingBeans) {
					return null;
				}
			};
			//如果descriptor的依赖类型是Optional类
			if (this.optional) {
				//创建Optional类型的符合descriptor要求的候选Bean对象并返回出去
				return createOptionalDependency(descriptorToUse, this.beanName);
			} else {
				//解析出与descriptor副本所包装的对象匹配的候选Bean对象
				return doResolveDependency(descriptorToUse, this.beanName, null, null);
			}
		}

		/**
		 * 获取与descriptor所包装的对象匹配的候选Bean对象
		 * <o>
		 * <li>如果descriptor的依赖类型是Optional类,创建Optional类型的符合
		 * descriptor要求的候选Bean对象并返回出去</li>
		 * <li>否则,解析出与descriptor副本所包装的对象匹配的候选Bean对象并返回出去</li>
		 * </o>
		 *
		 * @return 与descriptor所包装的对象匹配的候选Bean对象
		 * @throws BeansException 如果出现创建错误，或者获取不到候选Bean对象
		 */
		@Nullable
		protected Object getValue() throws BeansException {
			//如果descriptor的依赖类型是Optional类
			if (this.optional) {
				//创建Optional类型的符合descriptor要求的候选Bean对象并返回出去
				return createOptionalDependency(this.descriptor, this.beanName);
			} else {
				//解析出与descriptor副本所包装的对象匹配的候选Bean对象并返回出去
				return doResolveDependency(this.descriptor, this.beanName, null, null);
			}
		}

		/**
		 * 将与descriptor所包装的对象匹配的候选Bean对象包装成Stream对象，
		 * 没有特殊顺序保证（一般为注册顺序
		 */
		@Override
		public Stream<Object> stream() {
			//将与descriptor所包装的对象匹配的候选Bean对象包装成Stream对象，不需要排序
			return resolveStream(false);
		}

		/**
		 * 将与descriptor所包装的对象匹配的候选Bean对象包装成Stream对象，
		 * 采用@Order注解或实现Order接口的顺序
		 */
		@Override
		public Stream<Object> orderedStream() {
			//将与descriptor所包装的对象匹配的候选Bean对象包装成Stream对象，需要排序
			return resolveStream(true);
		}

		/**
		 * 将与descriptor所包装的对象匹配的候选Bean对象包装成Stream对象
		 * <ol>
		 *  <li>新建一个StreamDependencyDescriptor实例【变量 descriptorToUse】</li>
		 *  <li>解析出与descriptorToUse所包装的对象匹配的候选Bean对象【变量 result】</li>
		 *  <li>如果result是Stream实例,将result强转为Stream<object>对象返回出去;否则使用将result
		 *  包装成Stream对象返回出去</li>
		 * </ol>
		 *
		 * @param ordered 是否需要排序
		 * @return 将与descriptor所包装的对象匹配的候选Bean对象包装起来的Stream对象
		 */
		@SuppressWarnings("unchecked")
		private Stream<Object> resolveStream(boolean ordered) {
			//DependencyDescriptor:用于访问多个元素的流依赖项描述符标记，一般表示stream类型依赖
			//新建一个StreamDependencyDescriptor实例
			DependencyDescriptor descriptorToUse = new StreamDependencyDescriptor(this.descriptor, ordered);
			//解析出与descriptorToUse所包装的对象匹配的候选Bean对象
			Object result = doResolveDependency(descriptorToUse, this.beanName, null, null);
			//如果result是Stream实例,将result强转为Stream<object>对象返回出去;否则使用将result包装成Stream对象返回出去
			return (result instanceof Stream ? (Stream<Object>) result : Stream.of(result));
		}
	}

	/**
	 * <p>专门用于构建javax.inject.Provider对象的工厂</p>
	 * Separate inner class for avoiding a hard dependency on the {@code javax.inject} API.
	 * Actual {@code javax.inject.Provider} implementation is nested here in order to make it
	 * invisible for Graal's introspection of DefaultListableBeanFactory's nested classes.
	 * <p>单独的内部类，以避免对 java.inject API的严格依赖.实例的javax.inject.Provider实现
	 * 被嵌套在此处,以使Graal自省DefaultLisableBeanFactory的嵌套类时咯不到它</p>
	 */
	private class Jsr330Factory implements Serializable {

		/**
		 * 创建Jse330Provider的工厂方法
		 *
		 * @param descriptor 依赖项的描述符(字段/方法/构造函数)
		 * @param beanName   要依赖的Bean名,即需要Field/MethodParamter所对应的bean对象来构建的Bean对象的Bean名
		 * @return Jsr330Provider实例
		 */
		public Object createDependencyProvider(DependencyDescriptor descriptor, @Nullable String beanName) {
			//新建一个Jsr330Provider实例
			return new Jsr330Provider(descriptor, beanName);
		}

		/**
		 * javax.inject.Provider实现类
		 * <p>与DependencyObjectProvoid作用一样，也是用于延迟解析依赖项，但它是使用javax.inject.Provider作为依赖
		 * 对象，以减少与Springd耦合</p>
		 */
		private class Jsr330Provider extends DependencyObjectProvider implements Provider<Object> {

			/**
			 * 新建一个Jsr330Provider实例
			 *
			 * @param descriptor 依赖项的描述符(字段/方法/构造函数)
			 * @param beanName   要依赖的Bean名,即需要Field/MethodParamter所对应的bean对象来构建的Bean对象的Bean名
			 */
			public Jsr330Provider(DependencyDescriptor descriptor, @Nullable String beanName) {
				super(descriptor, beanName);
			}

			/**
			 * 获取与descriptor所包装的对象匹配的候选Bean对象
			 *
			 * @return 与descriptor所包装的对象匹配的候选Bean对象
			 * @throws BeansException 如果出现创建错误，或者获取不到候选Bean对象
			 */
			@Override
			@Nullable
			public Object get() throws BeansException {
				//获取与descriptor所包装的对象匹配的候选Bean对象
				return getValue();
			}
		}
	}

	/**
	 * <p>工厂感知排序源提供者：提供obj的Order来源,用于代替obj获取优先级值。主要Order来源:
	 *  <ul>
	 *    <li>obj对应的Bean名的合并后RootBeanDefinition的工厂方法对象</li>
	 *    <li>obj对应的Bean名的合并后RootBeanDefinition的目标类型</li>
	 *  </ul>
	 * </p>
	 * An {@link org.springframework.core.OrderComparator.OrderSourceProvider} implementation
	 * that is aware of the bean metadata of the instances to sort.
	 * <p>一个OrderComparator.OrderSourceProvider实现,该实现知道要排序的实例的Bean元数据</p>
	 * <p>Lookup for the method factory of an instance to sort, if any, and let the
	 * comparator retrieve the {@link org.springframework.core.annotation.Order}
	 * value defined on it. This essentially allows for the following construct:
	 * <p>查找要排序的实例的方法工厂(如果有),并让比较器检索在其上定义的
	 * org.springframwwork.core.annotation.Order的值.这本质上允许以下构造:</p>
	 */
	private class FactoryAwareOrderSourceProvider implements OrderComparator.OrderSourceProvider {

		/**
		 * 要排序的Bean对象Map，key=Bean名,value=Bean对象
		 */
		private final Map<Object, String> instancesToBeanNames;

		/**
		 * 新建一个FactoryAwareOrderSourceProvider实例
		 *
		 * @param instancesToBeanNames 要排序的Bean对象Map，key=Bean名,value=Bean对象
		 */
		public FactoryAwareOrderSourceProvider(Map<Object, String> instancesToBeanNames) {
			this.instancesToBeanNames = instancesToBeanNames;
		}

		/**
		 * 获取obj的Order来源,用于代替obj获取优先级值;来源主要是:
		 * <ul>
		 *  <li>obj对应的Bean名的合并后RootBeanDefinition的工厂方法对象</li>
		 *  <li>obj对应的Bean名的合并后RootBeanDefinition的目标类型</li>
		 * </ul>
		 * <ol>
		 *  <li>获取obj的bean名【变量 beanName】</li>
		 *  <li>如果beanName为null 或者 Beand定义对象映射【beanDefinitionMap】
		 *  中不存在beanName该键，返回null</li>
		 *  <li>获取beanName所对应的合并后RootBeanDefinition对象【变量 beanDefinition】</li>
		 *  <li>定义一个用于存储源对象的集合【变量 sources】</li>
		 *  <li>【<b>obj对应的Bean名的合并后RootBeanDefinition的工厂方法对象</b>】:
		 *   <ol>
		 *     <li>获取beanDefinition的工厂方法对象【变量 factoryMethod】</li>
		 *     <li>如果有factoryMethod,将factoryMethod添加到sources中</li>
		 *   </ol>
		 *  </li>
		 *  <li>【<b>obj对应的Bean名的合并后RootBeanDefinition的目标类型</b>】
		 *   <ol>
		 *    <li>获取beanDefinition的目标类型【targetType】</li>
		 *    <li>如果有目标类型且目标类型不是obj类型,将目标类型添加到sources</li>
		 *   </ol>
		 *  </li>
		 *  <li>将source装换成数组返回出去</li>
		 * </ol>
		 *
		 * @param obj the object to find an order source for
		 *            -- 查找Order来源的对象
		 * @return 源对象数组
		 */
		@Override
		@Nullable
		public Object getOrderSource(Object obj) {
			//获取obj的bean名
			String beanName = this.instancesToBeanNames.get(obj);
			//如果beanName为null 或者 Beand定义对象映射【beanDefinitionMap】中不存在beanName该键，
			if (beanName == null || !containsBeanDefinition(beanName)) {
				// 返回null
				return null;
			}
			//获取beanName所对应的合并后RootBeanDefinition对象
			RootBeanDefinition beanDefinition = getMergedLocalBeanDefinition(beanName);
			//定义一个用于存储源对象的集合
			List<Object> sources = new ArrayList<>(2);
			//获取beanDefinition的工厂方法对象
			Method factoryMethod = beanDefinition.getResolvedFactoryMethod();
			//如果有工厂方法对象
			if (factoryMethod != null) {
				//将工厂方法对象添加到sources中
				sources.add(factoryMethod);
			}
			//获取beanDefinition的目标类型
			Class<?> targetType = beanDefinition.getTargetType();
			//如果有目标类型且目标类型不是obj类型
			if (targetType != null && targetType != obj.getClass()) {
				//将目标类型添加到sources
				sources.add(targetType);
			}
			//将source装换成数组返回出去
			return sources.toArray();
		}
	}
}
