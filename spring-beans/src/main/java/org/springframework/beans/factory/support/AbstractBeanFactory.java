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

import org.springframework.beans.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.DecoratingClassLoader;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.log.LogMessage;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.*;

import java.beans.PropertyEditor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Abstract base class for {@link org.springframework.beans.factory.BeanFactory}
 * implementations, providing the full capabilities of the
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory} SPI.
 * Does <i>not</i> assume a listable bean factory: can therefore also be used
 * as base class for bean factory implementations which obtain bean definitions
 * from some backend resource (where bean definition access is an expensive operation).
 * <p>BeanFactory实现的抽象基类，提供了ConfigurableBeanFactory SPI的全部功能。
 * 不会假设有一个可列出的bean工厂:因此也可以用作bean工厂实现的基类，这些实现从某个后端资源获取bean
 * 定义(其中bean定义访问是一个昂贵的操作)。</p>
 *
 * <p>This class provides a singleton cache (through its base class
 * {@link org.springframework.beans.factory.support.DefaultSingletonBeanRegistry},
 * singleton/prototype determination, {@link org.springframework.beans.factory.FactoryBean}
 * handling, aliases, bean definition merging for child bean definitions,
 * and bean destruction ({@link org.springframework.beans.factory.DisposableBean}
 * interface, custom destroy methods). Furthermore, it can manage a bean factory
 * hierarchy (delegating to the parent in case of an unknown bean), through implementing
 * the {@link org.springframework.beans.factory.HierarchicalBeanFactory} interface.
 * <p>这个类提供了一个单例缓存(通过它的基类DefaultSingletonBeanRegistry、单例/原型确定、FactoryBean处理、
 * 别名、为子bean定义合并bean定义和bean销毁)。可处理bean接口，自定义销毁方法)
 * 此外，通过实现org.springframework.beans.factoryHierarchicalBeanFactory接口,可以管理
 * 一个bean工厂层次结构(在存在未知bean的情况下委托给父类)
 * </p>
 *
 * <p>The main template methods to be implemented by subclasses are
 * {@link #getBeanDefinition} and {@link #createBean}, retrieving a bean definition
 * for a given bean name and creating a bean instance for a given bean definition,
 * respectively. Default implementations of those operations can be found in
 * {@link DefaultListableBeanFactory} and {@link AbstractAutowireCapableBeanFactory}.
 * <p>由子类实现的主要模板方法是getBeanDefinition和createBean，分别检索给定bean名称的bean定义
 * 和为给定bean定义创建bean实例。这些操作的默认实现可以在DefaultListableBeanFactory和
 * AbstractAutowireCapableBeanFactory中找到。</p>
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @see #getBeanDefinition
 * @see #createBean
 * @see AbstractAutowireCapableBeanFactory#createBean
 * @see DefaultListableBeanFactory#getBeanDefinition
 * @since 15 April 2001
 */
public abstract class AbstractBeanFactory extends FactoryBeanRegistrySupport implements ConfigurableBeanFactory {

	/**
	 * Parent bean factory, for bean inheritance support.
	 */
	@Nullable
	private BeanFactory parentBeanFactory;

	/**
	 * ClassLoader to resolve bean class names with, if necessary.
	 * <p>必要时使用ClassLoader解析Bean类名称</p>
	 * <p>默认使用线程上下文类加载器</p>
	 */
	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	/**
	 * ClassLoader to temporarily resolve bean class names with, if necessary.
	 * 必要时使用ClassLoader临时解析Bean类名称
	 */
	@Nullable
	private ClassLoader tempClassLoader;

	/**
	 * Whether to cache bean metadata or rather reobtain it for every access.
	 * <p>是否缓存bean元数据还是每次访问重新获取它</p>
	 */
	private boolean cacheBeanMetadata = true;

	/**
	 * Resolution strategy for expressions in bean definition values.
	 * <p>bean定义值中表达式的解析策略</p>
	 * <p>SpringBoot默认使用的是StandardBeanExpressionResolver</p>
	 */
	@Nullable
	private BeanExpressionResolver beanExpressionResolver;

	/**
	 * <p>ConversionService:一个类型转换的服务接口。这个转换系统的入口。 调用convert(Object, Class)
	 * 去执行一个线程安全类型转换器 使用此系统。</p>
	 * <p>SpringBoot 默认引用 ApplicationConversionService</p>
	 * Spring ConversionService to use instead of PropertyEditors.
	 * <p>使用Spring ConversionService 代替PropertyEditors</p>
	 */
	@Nullable
	private ConversionService conversionService;

	/**
	 * Custom PropertyEditorRegistrars to apply to the beans of this factory.
	 * <p>定制PropertyEditorRegistrars应用于此工厂的bean。</p>
	 */
	private final Set<PropertyEditorRegistrar> propertyEditorRegistrars = new LinkedHashSet<>(4);

	/**
	 * Custom PropertyEditors to apply to the beans of this factory.
	 * <p>定制PropertyEditor应用于该工厂的bean</p>
	 */
	private final Map<Class<?>, Class<? extends PropertyEditor>> customEditors = new HashMap<>(4);

	/**
	 * A custom TypeConverter to use, overriding the default PropertyEditor mechanism.
	 * <p>要使用的自定义类型转换器，覆盖默认的PropertyEditor机制</p>
	 */
	@Nullable
	private TypeConverter typeConverter;

	/**
	 * String resolvers to apply e.g. to annotation attribute values.
	 * <p>字符串解析器适用于注解属性值</p>
	 * <p>SpringBoot默认存放一个PropertySourcesPlaceholderConfigurer，该类注意用于针对当前Spring Environment
	 * 及其PropertySource解析bean定义属性值和@Value注释中的${...}占位符</p>
	 */
	private final List<StringValueResolver> embeddedValueResolvers = new CopyOnWriteArrayList<>();

	/**
	 * BeanPostProcessors to apply in createBean.
	 * <p>BeanPosProcessor应用于createBean</p>
	 */
	private final List<BeanPostProcessor> beanPostProcessors = new BeanPostProcessorCacheAwareList();

	/**
	 * Cache of pre-filtered post-processors.
	 */
	@Nullable
	private BeanPostProcessorCache beanPostProcessorCache;

	/**
	 * Map from scope identifier String to corresponding Scope.
	 * <p> 从作用域表示符String映射到相应的作用域
	 */
	private final Map<String, Scope> scopes = new LinkedHashMap<>(8);

	/**
	 * Map from bean name to merged RootBeanDefinition.
	 * <p>从bean名称映射到合并的RootBeanDefinition
	 */
	private final Map<String, RootBeanDefinition> mergedBeanDefinitions = new ConcurrentHashMap<>(256);

	/**
	 * Names of beans that have already been created at least once.
	 * <p>至少已经创建一次的bean名称</p>
	 */
	private final Set<String> alreadyCreated = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	/**
	 * Names of beans that are currently in creation.
	 * <p>当前正在创建的bean名称</p>
	 */
	private final ThreadLocal<Object> prototypesCurrentlyInCreation =
			new NamedThreadLocal<>("Prototype beans currently in creation");

	/**
	 * Application startup metrics.
	 **/
	private ApplicationStartup applicationStartup = ApplicationStartup.DEFAULT;

	/**
	 * Create a new AbstractBeanFactory.
	 */
	public AbstractBeanFactory() {
	}

	/**
	 * Create a new AbstractBeanFactory with the given parent.
	 *
	 * @param parentBeanFactory parent bean factory, or {@code null} if none
	 * @see #getBean
	 */
	public AbstractBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		this.parentBeanFactory = parentBeanFactory;
	}


	//---------------------------------------------------------------------
	// Implementation of BeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public Object getBean(String name) throws BeansException {
		return doGetBean(name, null, null, false);
	}

	@Override
	public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
		return doGetBean(name, requiredType, null, false);
	}

	@Override
	public Object getBean(String name, Object... args) throws BeansException {
		return doGetBean(name, null, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * <p>返回一个实例，该实例可以指定bean的共享或独立</p>
	 *
	 * @param name         the name of the bean to retrieve
	 *                     -- 要检索的Bean名
	 * @param requiredType the required type of the bean to retrieve
	 *                     -- 检查所需的Bean类型
	 * @param args         arguments to use when creating a bean instance using explicit arguments
	 *                     (only applied when creating a new instance as opposed to retrieving an existing one)
	 *                     -- 使用显示参数创建Bean实例时要使用的参数(仅在创建新实例而不是检索现有实例时才应用)
	 * @return an instance of the bean -- Bean的一个实例
	 * @throws BeansException if the bean could not be created -- 如果无法创建Bean
	 */
	public <T> T getBean(String name, @Nullable Class<T> requiredType, @Nullable Object... args)
			throws BeansException {
		//返回一个实例，该实例可以指定bean的共享或独立
		return doGetBean(name, requiredType, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * <p>返回一个实例，该实例可以指定bean的共享或独立</p>
	 *
	 * @param name          the name of the bean to retrieve - 要检索的bean名称
	 * @param requiredType  the required type of the bean to retrieve - 检索所需的bean类型
	 * @param args          arguments to use when creating a bean instance using explicit arguments
	 *                      (only applied when creating a new instance as opposed to retrieving an existing one)
	 *                      -- 使用显示参数创建bean实例时要使用的参数（仅在创建新实例而不是检索现有实例时适用）
	 * @param typeCheckOnly whether the instance is obtained for a type check,
	 *                      not for actual use - 是否获取实例以进行类型检查，并非用于实际用途
	 * @return an instance of the bean - Bean的一个实例
	 * @throws BeansException if the bean could not be created - 如果无法创建该bean
	 */
	@SuppressWarnings("unchecked")
	protected <T> T doGetBean(
			String name, @Nullable Class<T> requiredType, @Nullable Object[] args, boolean typeCheckOnly)
			throws BeansException {

		// 获取一个 “正统的” beanName，处理两种情况，一个是前面说的 FactoryBean(前面带 ‘&’)，
		// 一个是别名问题，因为这个方法是 getBean，获取 Bean 用的，你要是传一个别名进来，是完全可以的
		String beanName = transformedBeanName(name);
		Object beanInstance;

		// Eagerly check singleton cache for manually registered singletons.
		// 检查下是不是已经创建过
		Object sharedInstance = getSingleton(beanName);
		// 处理构造函数
		if (sharedInstance != null && args == null) {
			if (logger.isTraceEnabled()) {
				if (isSingletonCurrentlyInCreation(beanName)) {
					logger.trace("Returning eagerly cached instance of singleton bean '" + beanName +
							"' that is not fully initialized yet - a consequence of a circular reference");
				} else {
					logger.trace("Returning cached instance of singleton bean '" + beanName + "'");
				}
			}
			//如果是普通 Bean 的话，直接返回 sharedInstance，如果是 FactoryBean 的话，返回它创建的那个实例对象
			beanInstance = getObjectForBeanInstance(sharedInstance, name, beanName, null);
		} else {
			// Fail if we're already creating this bean instance:
			// We're assumably within a circular reference.
			// 如果指定beanName是原型bean，并且当前正在创建中（在当前线程内），而现在又在被创建，说明出现了循环引用，那么抛出异常
			// Spring可以帮我们解决setter方法和反射方法的循环依赖注入，但是互相依赖的两个bean不能都是prototype的
			if (isPrototypeCurrentlyInCreation(beanName)) {
				//创建过了此 beanName 的 prototype 类型的 bean，那么抛异常，往往是因为陷入了循环引用
				throw new BeanCurrentlyInCreationException(beanName);
			}

			// Check if bean definition exists in this factory.
			//对IOC容器中是否存在指定名称的BeanDefinition进行检查，首先检查是否
			//能在当前的BeanFactory中获取的所需要的Bean，如果不能则委托当前容器
			//的父级容器去查找，如果还是找不到则沿着容器的继承体系向父级容器查找
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
				// Not found -> check parent.
				// 如果当前容器不存在这个 BeanDefinition，试试父容器中有没有
				String nameToLookup = originalBeanName(name);
				if (parentBeanFactory instanceof AbstractBeanFactory abf) {
					// 返回父容器的查询结果
					return abf.doGetBean(nameToLookup, requiredType, args, typeCheckOnly);
				} else if (args != null) {
					// Delegation to parent with explicit args.
					// 返回父容器的查询结果
					return (T) parentBeanFactory.getBean(nameToLookup, args);
				} else if (requiredType != null) {
					// No args -> delegate to standard getBean method.
					// 返回父容器的查询结果
					return parentBeanFactory.getBean(nameToLookup, requiredType);
				} else {
					// 返回父容器的查询结果
					return (T) parentBeanFactory.getBean(nameToLookup);
				}
			}
			// 创建的Bean是否需要进行类型验证，一般不需要
			if (!typeCheckOnly) {
				// typeCheckOnly 为 false，将当前 beanName 放入一个 alreadyCreated 的 Set 集合中
				markBeanAsCreated(beanName);
			}

			StartupStep beanCreation = this.applicationStartup.start("spring.beans.instantiate")
					.tag("beanName", name);


			// 到这里的话，要准备创建 Bean 了，对于 singleton 的 Bean 来说，容器中还没创建过此 Bean；
			// 对于 prototype 的 Bean 来说，本来就是要创建一个新的 Bean。
			try {
				if (requiredType != null) {
					beanCreation.tag("beanType", requiredType::toString);
				}
				//从容器中获取 beanName 相应的 GenericBeanDefinition 对象，并将其转换为 RootBeanDefinition 对象主要解决Bean继承时子类合并父类公共属性问题
				RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
				// 检查给定的合并的 BeanDefinition (是否为抽象类)
				checkMergedBeanDefinition(mbd, beanName, args);

				// Guarantee initialization of beans that the current bean depends on.
				// 先初始化依赖的所有 Bean， 注意，这里的依赖指的是 depends-on 中定义的依赖
				String[] dependsOn = mbd.getDependsOn();
				if (dependsOn != null) {
					for (String dep : dependsOn) {
						// 检查是不是有循环依赖，这里的循环依赖和我们前面说的循环依赖又不一样，这里肯定是不允许出现的，不然要乱套了
						if (isDependent(beanName, dep)) {
							//已注册，抛出异常
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"Circular depends-on relationship between '" + beanName + "' and '" + dep + "'");
						}
						// 注册一下依赖关系
						registerDependentBean(dep, beanName);
						try {
							// 先初始化被依赖项
							getBean(dep);
						} catch (NoSuchBeanDefinitionException ex) {
							throw new BeanCreationException(mbd.getResourceDescription(), beanName,
									"'" + beanName + "' depends on missing bean '" + dep + "'", ex);
						}
					}
				}

				// Create bean instance.
				// 如果是 singleton scope 的，创建 singleton 的实例
				if (mbd.isSingleton()) {
					//调用getSingleton方法获取实例，这里是真正的完整bean实例创建的方法
					//第二个参数使用了Java8的lambda表达式语法，ObjectFactory的getObject方法调用的是createBean方法
					sharedInstance = getSingleton(beanName, () -> {
						try {
							// 执行创建 Bean
							return createBean(beanName, mbd, args);
						} catch (BeansException ex) {
							// Explicitly remove instance from singleton cache: It might have been put there
							// eagerly by the creation process, to allow for circular reference resolution.
							// Also remove any beans that received a temporary reference to the bean.
							//抛出异常之后，从单例缓存中删除对应的实例
							destroySingleton(beanName);
							throw ex;
						}
					});
					//返回sharedInstance对应的实例对象
					//如果是普通bean实例，那么直接返回sharedInstance，如果是 FactoryBean 实例，则获取FactoryBean实例本身或其创建的对象
					beanInstance = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
				}
				// 如果是 prototype scope 的，创建 prototype 的实例
				else if (mbd.isPrototype()) {
					// It's a prototype -> create a new instance.
					Object prototypeInstance = null;
					try {
						//在原型bean实例创建之前进行回调
						//默认实现是将当前原型beanName注册到当前正在创建的bean缓存prototypesCurrentlyInCreation中
						beforePrototypeCreation(beanName);
						// 执行创建 Bean
						prototypeInstance = createBean(beanName, mbd, args);
					} finally {
						//在原型bean实例创建之后进行回调
						//默认实现将原型标记为不在创建中，即从当前正在创建的bean缓存prototypesCurrentlyInCreation中移除
						afterPrototypeCreation(beanName);
					}
					//返回sharedInstance对应的实例对象
					//如果是普通bean实例，那么直接返回sharedInstance，如果是 FactoryBean 实例，则获取FactoryBean实例本身或其创建的对象
					beanInstance = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
				}
				// 如果不是 singleton 和 prototype 的话，需要委托给相应的实现类来处理
				else {
					//获取scope属性值
					String scopeName = mbd.getScope();
					//scopeName不能为null以及""
					if (!StringUtils.hasLength(scopeName)) {
						throw new IllegalStateException("No scope name defined for bean '" + beanName + "'");
					}
					//从scopes缓存中根据scopeName获取scope
					Scope scope = this.scopes.get(scopeName);
					if (scope == null) {
						throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
					}
					try {
						//使用各自的scope实现来创建scopedInstance实例
						Object scopedInstance = scope.get(beanName, () -> {
							//在原型bean实例创建之前进行回调
							//默认实现是将当前原型beanName注册到当前正在创建的bean缓存prototypesCurrentlyInCreation中
							beforePrototypeCreation(beanName);
							try {
								// 执行创建 Bean
								return createBean(beanName, mbd, args);
							} finally {
								//在原型bean实例创建之后进行回调
								//默认实现将原型标记为不在创建中，即从当前正在创建的bean缓存prototypesCurrentlyInCreation中移除
								afterPrototypeCreation(beanName);
							}
						});
						//返回sharedInstance对应的实例对象
						//如果是普通bean实例，那么直接返回sharedInstance，如果是 FactoryBean 实例，则获取FactoryBean实例本身或其创建的对象
						beanInstance = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
					} catch (IllegalStateException ex) {
						throw new ScopeNotActiveException(beanName, scopeName, ex);
					}
				}
			} catch (BeansException ex) {
				beanCreation.tag("exception", ex.getClass().toString());
				beanCreation.tag("message", String.valueOf(ex.getMessage()));
				//bean创建失败之后，将beanName从alreadyCreated缓存中移除
				cleanupAfterBeanCreationFailure(beanName);
				throw ex;
			} finally {
				beanCreation.end();
			}
		}
		// 检查类型对不对，不对的话就抛异常，对的话就返回
		return adaptBeanInstance(name, beanInstance, requiredType);
	}

	@SuppressWarnings("unchecked")
	<T> T adaptBeanInstance(String name, Object bean, @Nullable Class<?> requiredType) {
		// Check if required type matches the type of the actual bean instance.
		//检查所需的目标类型是否与bean 实例的类型相匹配或者兼容，这里的兼容的要求是bean实例属于requiredType的类型以及子类
		//如果不匹配或者兼容，那么使用转换服务进行类型转换，如果兼容就直接返回bean
		if (requiredType != null && !requiredType.isInstance(bean)) {
			try {
				//转换类型
				Object convertedBean = getTypeConverter().convertIfNecessary(bean, requiredType);
				if (convertedBean == null) {
					throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
				}
				return (T) convertedBean;
			} catch (TypeMismatchException ex) {
				if (logger.isTraceEnabled()) {
					logger.trace("Failed to convert bean '" + name + "' to required type '" +
							ClassUtils.getQualifiedName(requiredType) + "'", ex);
				}
				throw new BeanNotOfRequiredTypeException(name, requiredType, bean.getClass());
			}
		}
		return (T) bean;
	}

	/**
	 * 该bean工厂是否包含具有给定名称的bean定义或外部注册的singleton实例：
	 * <ol>
	 *     <li>获取name最终的规范名称【最终别名】,as beanName</li>
	 *     <li>如果beanName存在于singletonObjects【单例对象的高速缓存Map集合】中,
	 *     或者 从beanDefinitionMap【Beand定义对象映射】中存在该beanName的BeanDefinition对象,TODO</li>
	 *     <li>获取父工厂,以递归形式调用相同方法查询该name是否存在于父工厂，并返回执行结果；</li>
	 * </ol>
	 *
	 * @param name the name of the bean to query -- 要查询的bean名
	 * @return -- 是否存在给定名称的bean
	 */
	@Override
	public boolean containsBean(String name) {
		//获取name最终的规范名称【最终别名】
		String beanName = transformedBeanName(name);
		//如果beanName存在于singletonObjects【单例对象的高速缓存Map集合】中，
		// 或者 从beanDefinitionMap【Bean定义对象映射】中存在该beanName的BeanDefinition对象
		if (containsSingleton(beanName) || containsBeanDefinition(beanName)) {
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(name));
		}
		// Not found -> check parent.
		//获取父工厂
		BeanFactory parentBeanFactory = getParentBeanFactory();
		//如果父工厂不为null 则递归形式查询该name是否存在于父工厂，并返回执行结果；为null时直接返回false
		// 因为经过上面步骤，已经确定当前工厂不存在该bean的BeanDefinition对象以及singleton实例
		return (parentBeanFactory != null && parentBeanFactory.containsBean(originalBeanName(name)));
	}

	/**
	 * 判断给定的name所指的对象是否为单例：
	 * <ol>
	 *   <li>去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】【变量 beanName】</li>
	 *   <li><b>处理name在工厂已经有相关的单例对象：</b>
	 *    <ol>
	 *     <li>在不允许创建早期引用的情况下，获取beanName所指的对象 【变量 beanInstance】</li>
	 *     <li>如果成功获取beanInstance:
	 *      <ol>
	 *       <li>如果beanInstance是FactoryBean实例,将 name是否是FactoryBean的解引用的结果返回出去
	 *       或者 将beanInstance强转成FactoryBean对象后，调用isSingleton()得到是否为单例的结果返回出去</li>
	 *       <li>否则，获取name是否是FactoryBean的解引用的结果，是就返回表示不是单例，否则表示单例</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li><b>处理该工厂没有name对应的beanDefinition对象但有父工厂的情况:</b>获取父工厂,先获取name对应的
	 *   规范名称【全类名/最终别名】，然后使用父工厂调用该方法判断name，将结果返回出去</li>
	 *   <li>
	 *     <b>处理该工厂有name对应的BeanDefinition对象情况</b>
	 *     <ol>
	 *       <li>获取bean的合并后的RootBeanDefinition对象 【变量mbd】</li>
	 *       <li>如果mbd配置的作用域是单例
	 *        <ol>
	 *          <li>如果beanName,mbd所指的bean是FactoryBean:
	 *           <ol>
	 *             <li>获取name是FactoryBean的解引用的则认为是单例，返回true</li>
	 *             <li>获取name所指的FactoryBean对象【变量 factoryBean】</li>
	 *             <li>将factoryBean所创建的Bean对象是否为单例的结果返回出去</li>
	 *           </ol>
	 *          </li>
	 *          <li>获取name是否是FactoryBean的解引用的结果，是就返回表示不是单例，否则表示单例</li>
	 *        </ol>
	 *       </li>
	 *       <li>如果mbd配置的作用域不是单例，返回false</li>
	 *     </ol>
	 *   </li>
	 * </ol>
	 *
	 * @param name the name of the bean to query -- 要查询的bean名
	 * @return 此bean是否对应于一个单例实例
	 * @throws NoSuchBeanDefinitionException 如果没有给定名称的bean
	 */
	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {

		//去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】
		String beanName = transformedBeanName(name);
		//在不允许创建早期引用的情况下，获取beanName所指的对象
		Object beanInstance = getSingleton(beanName, false);
		//如果成功获取beanInstance
		if (beanInstance != null) {
			//如果beanInstance是FactoryBean实例
			if (beanInstance instanceof FactoryBean<?> factoryBean) {
				//将 name是否是FactoryBean的解引用的结果返回出去 或者 将beanInstance强转成FactoryBean对象后，调用isSingleton()得到是否为
				// 单例的结果返回出去
				return (BeanFactoryUtils.isFactoryDereference(name) || factoryBean.isSingleton());
			} else {
				//获取name是否是FactoryBean的解引用的结果，是就返回表示不是单例，否则表示单例
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}

		// No singleton instance found -> check bean definition.
		// 找不到单例实例 -> 检查bean定义
		// 获取父工厂
		BeanFactory parentBeanFactory = getParentBeanFactory();
		//如果成功获取到父工厂 且 当前工厂没有beanName所指的BeanDefinitionduix
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			// 在此工厂中找不到bean定义 -> 委托给父对象。
			// 使用父工厂调用该方法判断name，将结果返回出去
			return parentBeanFactory.isSingleton(originalBeanName(name));
		}

		//获取bean的合并后的RootBeanDefinition对象
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// In case of FactoryBean, return singleton status of created object if not a dereference.
		// 对于FactoryBean，如果不取消引用，则返回创建对象的单例状态
		// 如果mbd配置的作用域是单例
		if (mbd.isSingleton()) {
			//如果beanName,mbd所指的bean是FactoryBean
			if (isFactoryBean(beanName, mbd)) {
				//获取name是FactoryBean的解引用的则认为是单例，返回true
				if (BeanFactoryUtils.isFactoryDereference(name)) {
					return true;
				}
				//获取name所指的FactoryBean对象
				FactoryBean<?> factoryBean = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
				//将factoryBean所创建的Bean对象是否为单例的结果返回出去
				return factoryBean.isSingleton();
			} else {
				//获取name是否是FactoryBean的解引用的结果，是就返回表示不是单例，否则表示单例
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		} else {
			//如果mbd配置的作用域不是单例，返回false
			return false;
		}
	}

	@Override
	public boolean isPrototype(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isPrototype(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isPrototype()) {
			// In case of FactoryBean, return singleton status of created object if not a dereference.
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName, mbd));
		}

		// Singleton or scoped - not a prototype.
		// However, FactoryBean may still produce a prototype object...
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			return false;
		}
		if (isFactoryBean(beanName, mbd)) {
			FactoryBean<?> fb = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
			return ((fb instanceof SmartFactoryBean<?> smartFactoryBean && smartFactoryBean.isPrototype()) ||
					!fb.isSingleton());
		} else {
			return false;
		}
	}

	@Override
	public boolean isTypeMatch(String name, ResolvableType typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, typeToMatch, true);
	}

	/**
	 * <p>检查具有给定名称的bean是否与指定的类型匹配:
	 *  <ol>
	 *    <li>去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】【变量 beanName】</li>
	 *    <li>判断name是否为FactoryBean的解引用名(name是以'&'开头，就是FactoryBean的解引用)【变量 isFactoryDereference】</li>
	 *    <li>获取beanName的单例对象，但不会创建引用【变量 beanInstance】</li>
	 *    <li><b>该工厂已经有name对应的单例对象的情况.</b>如果成功获取到beanInstance 而且 该单例对象的类型又不是NullBean：
	 *     <ol>
	 *       <li>如果beanInstance是FactoryBean的实例:
	 *        <ol>
	 *          <li>如果name不是FactoryBean的解引用名：
	 *           <ol>
	 *             <li>获取beanInstance的创建出来的对象的类型【变量 type】.如果成功获取到type 而且属于typeToMatch就返回true；
	 *             否则返回false</li>
	 *             <li>否则，通过调用typeToMatch.isInstance(beanInstance)判断beanInstance是否属于通过typeToMatch的实例并
	 *             将结果返回出去。</li>
	 *           </ol>
	 *          </li>
	 *        </ol>
	 *       </li>
	 *          <li>如果name不是FactoryBean的解引用名:
	 *           <ol>
	 *             <li>如果通过调用typeToMatch.isInstance(beanInstance)得到的结果是beanInstance属于要typeToMatch实例
	 *             就直接返回true</li>
	 *             <li>如果typeToMatch包含泛型参数 而且 该工厂包含beanName所对应的BeanDefinition对象。【意味着可以通过BeanDefinition的
	 *             targetType或者factoryMethodReturnType判断】:
	 *              <ol>
	 *                <li>获取beanName所对应的合并RootBeanDefinition,【变量 mbd】</li>
	 *                <li>获取mbd的目标类型 【变量 targetType】</li>
	 *                <li>如果成功获取到了targetType而且 targetType与beanInstance的类型不同:
	 *                  <ol>
	 *                    <li>获取TypetoMatch封装的Class对象 【变量 classToMatch】</li>
	 *                    <li>如果成功获取classToMatch而且beanInstance不是classToMatch的实例，返回false</li>
	 *                    <li>如果targetType属于typeToMatch，返回true</li>
	 *                  </ol>
	 *                </li>
	 *                <li>获取mbd的目标类型 【变量 resolvableType】</li>
	 *                <li>如果获取不到resolvableType就获取mbd的工厂方法返回类型作为mbd的目标类型作为resolvableType</li>
	 *                <li>如果成功获取到了resolvableType 而且该resolvableType属于typeToMatch 就返回true，否则返回false。</li>
	 *              </ol>
	 *             </li>
	 *           </ol>
	 *          </li>
	 *          <li>否则返回false（beanInstance不是FactoryBean的实例 或者 name是FactoryBean的解引用名）</li>
	 *     </ol>
	 *    </li>
	 *    <li><b>处理工厂有beanName的实例而实例是NullBean以及工厂没有beanName的BeanDefinition的情况：</b>
	 *       如果该工厂的单例对象注册器包含beanName所指的单例对象 但该工厂没有beanName对应的BeanDefinition对象，就认为是beanName
	 *       对应的实例是NullBean实例，因前面已经处理了beanName不是NullBean的情况，再加上该工厂没有对应beanName
	 *       的BeanDefinition对象.</li>
	 *    <li><b>处理有父工厂以及父工厂有beanName的BeanDefinition对象的情况：</b>获取该工厂的父级工厂，【变量 parentBeanFactory】；
	 *    如果parentBeanFactory不为null 且 该工厂没有包含beanName的BeanDefinition，递归交给父工厂判断，将判断结果返回出去。</li>
	 *    <li><b>处理需要获取mdb所指的最终类型情况：</b>：
	 *     <ol>
	 *       <li>获取beanName合并后的本地RootBeanDefintion 【变量 mbd】</li>
	 *       <li>获取mbd的BeanDefinitionHolder 【变量dbd】</li>
	 *       <li>获取typeToMatch封装的Class,获取不到用FactoryBean【变量 classToMatch】</li>
	 *       <li>如果facatoryBean不是classToMatch就加上FactoryBean.class</li>
	 *       <li>【获取预测类型】定义预测类型【变量 predictedType】：
	 *        <ol>
	 *           <li>如果不是FactoryBean解引用 且 dbd不为null 且 beanName和mbd所指的bean是FactoryBean。【即：
	 * 	          可以预测dbd所封装的信息所指Bean类型赋值给 predictedType】:
	 * 	          <ol>
	 * 	            <li>如果 mbd没有设置lazy-init 或者 允许FactoryBean初始化:
	 * 	              <ol>
	 * 	                <li>获取dbd的beanName，dbd的BeanDefinition，mbd所对应的合并后RootBeanDefinition 【变量tbd】</li>
	 * 	                <li>预测dbd的beanName,tbd,typesToMatch的Bean类型 【变量 targetType】</li>
	 * 	                <li>如果目标类型不为null 且 targetType不属于FactoryBean,predictedType就为targetType</li>
	 * 	              </ol>
	 * 	            </li>
	 * 	          </ol>
	 * 	         </li>
	 * 	         <li>如果predictedType为null，获取beanName，mbd，typeToMatch所对应的Bean类型作为predictedType，获取不了
	 * 	          就返回false.【这个时候得到的predictedType有可能是FactoryBean类而不是最终类型】</li>
	 * 	      </ol>
	 * 	     </li>
	 * 	     <li>【获取最终的Bean类型】定义一个Bean的最终ResolvableType 【变量beanType】:
	 * 	      <ol>
	 * 	        <li>如果predictedType属于FactoryBean类：
	 * 	         <ol>
	 * 	           <li>如果没有beanInstance 且 beanName不是指FactoryBean解引用：
	 * 	             <ol>
	 * 	               <li>获取beanName,mbd所指的FactoryBean要创建的bean类型赋值给beanType </li>
	 * 	               <li>获取beanType封装的Class对象赋值给predictedType,如果predictedType为null，就返回false</li>
	 * 	             </ol>
	 * 	           </li>
	 * 	         </ol>
	 * 	        </li>
	 * 	       <li>如果beanName是指FactoryBean解引用:
	 * 	        <ol>
	 * 	          <li>预测mdb所指的bean的最终bean类型赋值给predictedType</li>
	 * 	          <li>如果predictedType为null 或者 得到的预测类型属于FactoryBean,则返回false，表示不匹配</li>
	 * 	        </ol>
	 * 	       </li>
	 * 	       <li>如果没有拿到beanType,声明一个已定义类型【变量 definedType】，默认使用mbd的目标类型，
	 * 	       拿不到用mbd的工厂方法的返回类型</li>
	 * 	       <li>如果拿到了definedType 且 definedType所封装的Class对象与predictedType相同,
	 * 	       beanType引用definedType</li>
	 * 	     </ol>
	 * 	    </li>
	 * 	    <li>如果拿到了beanType,返回 beanType是否属于typeToMatch的结果,否则返回predictedType否属于typeToMatch的结果</li>
	 *     </ol>
	 *    </li>
	 *  </ol>
	 * </p>
	 * Internal extended variant of {@link #isTypeMatch(String, ResolvableType)}
	 * to check whether the bean with the given name matches the specified type. Allow
	 * additional constraints to be applied to ensure that beans are not created early.
	 * <p>isTypeMatch(String,ResolvableType)的内部扩展变体，用于检查具有给定名称的bean是否
	 * 与指定的类型匹配。允许应用其他约束，以确保不及早创建bean</p>
	 *
	 * @param name                 the name of the bean to query -- 要查询的bean名称
	 * @param typeToMatch          the type to match against (as a
	 *                             {@code ResolvableType}) -- 要匹配的类型(作为 ResolvableType)
	 * @param allowFactoryBeanInit 是否允许FactoryBean初始化
	 * @return {@code true} if the bean type matches, {@code false} if it
	 * doesn't match or cannot be determined yet
	 * -- 如果bean类型匹配，则为true，如果不匹配或尚未确定，则为false
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @see #getBean
	 * @see #getType
	 * @since 5.2
	 */
	protected boolean isTypeMatch(String name, ResolvableType typeToMatch, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		//去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】
		String beanName = transformedBeanName(name);
		//判断name是否为FactoryBean的解引用名
		//name是以'&'开头，就是FactoryBean的解引用
		boolean isFactoryDereference = BeanFactoryUtils.isFactoryDereference(name);

		// Check manually registered singletons.
		// 检查手动注册的单例
		//获取beanName的单例对象，但不允许创建引用
		Object beanInstance = getSingleton(beanName, false);
		//如果成功获取到单例对象 而且 该单例对象的类型又不是NullBean
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			//如果单例对象是FactoryBean的实例
			if (beanInstance instanceof FactoryBean<?> factoryBean) {
				//如果name不是FactoryBean的解引用名
				if (!isFactoryDereference) {
					//如果name不是FactoryBean的解引用名
					Class<?> type = getTypeForFactoryBean(factoryBean);
					//如果成功获取到beanInstance的创建出来的对象的类型 而且属于要匹配的类型
					return (type != null && typeToMatch.isAssignableFrom(type));
				} else {
					//如果成功获取到beanInstance的创建出来的对象的类型 而且属于要匹配的类型
					return typeToMatch.isInstance(beanInstance);
				}
			}
			//如果成功获取到beanInstance的创建出来的对象的类型 而且属于要匹配的类型
			else if (!isFactoryDereference) {
				//如果成功获取到beanInstance的创建出来的对象的类型 而且属于要匹配的类型
				if (typeToMatch.isInstance(beanInstance)) {
					// Direct match for exposed instance?
					//直接匹配暴露的实例？
					return true;
				}
				//如果要匹配的类型包含泛型参数 而且 此bean工厂包含beanName所指的BeanDefinition定义
				else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
					// Generics potentially only match on the target class, not on the proxy...
					// 泛型可能仅在目标类上匹配，而在代理上不匹配
					//获取beanName所对应的合并RootBeanDefinition
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					//获取mbd的目标类型
					Class<?> targetType = mbd.getTargetType();
					//如果成功获取到了mbd的目标类型 而且 目标类型 与 单例对象的类型不同
					if (targetType != null && targetType != ClassUtils.getUserClass(beanInstance)) {
						// Check raw class match as well, making sure it's exposed on the proxy.
						// 同时检查原始类匹配，确保它在代理中公开
						//获取TypeToMatch的封装Class对象
						Class<?> classToMatch = typeToMatch.resolve();
						//如果成功获取Class对象 而且 单例对象不是该Class对象的实例
						if (classToMatch != null && !classToMatch.isInstance(beanInstance)) {
							//表示要查询的Bean名与要匹配的类型不匹配
							return false;
						}
						//如果mbd的目标类型属于要匹配的类型
						if (typeToMatch.isAssignableFrom(targetType)) {
							//表示要查询的Bean名与要匹配的类型匹配
							return true;
						}
					}
					//获取mbd的目标类型
					ResolvableType resolvableType = mbd.targetType;
					//如果获取mbd的目标类型失败
					if (resolvableType == null) {
						//获取mbd的工厂方法返回类型作为mbd的目标类型
						resolvableType = mbd.factoryMethodReturnType;
					}
					//如果成功获取到了mbd的目标类型 而且该目标类型属于要匹配的类型 就返回true，否则返回false。
					return (resolvableType != null && typeToMatch.isAssignableFrom(resolvableType));
				}
			}
			//如果beanName的单例对象不是FactoryBean的实例 或者 name是FactoryBean的解引用名
			return false;
		}
		//如果该工厂的单例对象注册器包含beanName所指的单例对象 但该工厂没有beanName对应的BeanDefinition对象
		else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// null instance registered
			// 注册了null实例,即 beanName对应的实例是NullBean实例，因前面已经处理了beanName不是NullBean的情况，
			//  再加上该工厂没有对应beanName的BeanDefinition对象
			return false;
		}

		// No singleton instance found -> check bean definition.
		// 找不到单例实例 -> 检查bean定义
		//获取该工厂的父级工厂
		BeanFactory parentBeanFactory = getParentBeanFactory();
		//如果父级工厂不为null 且 该工厂没有包含beanName的BeanDefinition
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			// 在该工厂中找不到BeanDefinition -> 委托给父对象
			// 递归交给父工厂判断，将判断结果返回出去
			return parentBeanFactory.isTypeMatch(originalBeanName(name), typeToMatch);
		}

		// Retrieve corresponding bean definition.
		// 检索相应的bean定义
		// 获取beanName合并后的本地RootBeanDefintiond
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		// 获取mbd的BeanDefinitionHolder
		// BeanDefinitionHolder就是对BeanDefinition的持有，同时持有的包括BeanDefinition的名称和别名
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();

		// Setup the types that we want to match against
		// 设置我们要匹配的类型
		// 获取我们要匹配的class对象
		Class<?> classToMatch = typeToMatch.resolve();
		//如果classToMatch为null
		if (classToMatch == null) {
			//默认使用FactiongBean作为要匹配的class对象
			classToMatch = FactoryBean.class;
		}
		//如果FactoryBean不是要匹配的class对象，要匹配的类数组会加上FactoryBean.class
		Class<?>[] typesToMatch = (FactoryBean.class == classToMatch ?
				new Class<?>[]{classToMatch} : new Class<?>[]{FactoryBean.class, classToMatch});


		// Attempt to predict the bean type
		// 尝试预测bean类型
		Class<?> predictedType = null;

		// We're looking for a regular reference but we're a factory bean that has
		// a decorated bean definition. The target bean should be the same type
		// as FactoryBean would ultimately return.
		// 我们正在寻找常规参考，但是我们是具有修饰的BeanDefinition的FactoryBean.目标bean类型
		// 应与factoryBean最终返回的类型相同
		// 如果不是FactoryBean解引用 且 mbd有配置BeanDefinitionHolder 且 beanName,mbd所指的bean是FactoryBean
		if (!isFactoryDereference && dbd != null && isFactoryBean(beanName, mbd)) {
			// We should only attempt if the user explicitly set lazy-init to true
			// and we know the merged bean definition is for a factory bean.
			// 只有在用户将lazy-init显示设置为true并且我们知道合并的BeanDefinition是针对FactoryBean的情况下，才应该尝试
			// 如果 mbd没有设置lazy-init 或者 允许FactoryBean初始化
			if (!mbd.isLazyInit() || allowFactoryBeanInit) {
				//获取dbd的beanName，dbd的BeanDefinition，mbd所对应的合并后RootBeanDefinition
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				//预测dbd的beanName,tbd,typesToMatch的Bean类型
				Class<?> targetType = predictBeanType(dbd.getBeanName(), tbd, typesToMatch);
				//如果目标类型不为null，且 targetType不属于FactoryBean
				if (targetType != null && !FactoryBean.class.isAssignableFrom(targetType)) {
					//预测bean类型就为该目标类型
					predictedType = targetType;
				}
			}
		}

		// If we couldn't use the target type, try regular prediction.
		// 如果我们无法使用目标类型，请尝试常规预测
		// 如果无法获得预测bean类型
		if (predictedType == null) {
			//获取beanName，mbd，typeToMatch所对应的Bean类型作为预测bean类型
			predictedType = predictBeanType(beanName, mbd, typesToMatch);
			//如果没有成功获取到预测bean类型，返回false，表示不匹配
			if (predictedType == null) {
				return false;
			}
		}

		// Attempt to get the actual ResolvableType for the bean.
		// 尝试获取Bean的实际ResolvableType
		// ResolvableType：可以看作是封装JavaType的元信息类
		ResolvableType beanType = null;

		// If it's a FactoryBean, we want to look at what it creates, not the factory class.
		// 如果是FactoryBean,我们要查看它创建的内容，而不是工厂类
		// 如果predictedType属于FactoryBean
		if (FactoryBean.class.isAssignableFrom(predictedType)) {
			//如果没有beanName的单例对象 且 beanName不是指FactoryBean解引用
			if (beanInstance == null && !isFactoryDereference) {
				//获取beanName,mbd的FactoryBean定义的bean类型赋值给beanType
				beanType = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit);
				//解析beanType以得到predictedType
				predictedType = beanType.resolve();
				//如果得到predictedType为null
				if (predictedType == null) {
					//返回false，表示不匹配
					return false;
				}
			}
		} else if (isFactoryDereference) {
			// Special case: A SmartInstantiationAwareBeanPostProcessor returned a non-FactoryBean
			// type, but we nevertheless are being asked to dereference a FactoryBean...
			// Let's check the original bean class and proceed with it if it is a FactoryBean.
			// 特殊情况：SmartInstantiationAwareBeanPostProcessor返回非FactoryBean类型，但是仍然要求我们
			// 取消引用FactoryBean... 让我们检查原始bean类，如果它是FactoryBean，则继续进行处理
			// 预测mdb所指的bean的最终bean类型
			predictedType = predictBeanType(beanName, mbd, FactoryBean.class);
			//如果预测不到 或者 得到的预测类型属于FactoryBean
			if (predictedType == null || !FactoryBean.class.isAssignableFrom(predictedType)) {
				//返回false，表示不匹配
				return false;
			}
		}

		// We don't have an exact type but if bean definition target type or the factory
		// method return type matches the predicted type then we can use that.
		//我们没有确切的类型，但是如果bean定义目标类型或者工厂方法返回类型与预测的类型匹配，则可以使用它
		// 如果没有拿到beanType
		if (beanType == null) {
			//声明一个已定义类型，默认使用mbd的目标类型
			ResolvableType definedType = mbd.targetType;
			//如果没有拿到definedType
			if (definedType == null) {
				//获取mbd的工厂方法的返回类型
				definedType = mbd.factoryMethodReturnType;
			}
			//如果拿到了definedType 且 definedType所封装的Class对象与预测类型相同
			if (definedType != null && definedType.resolve() == predictedType) {
				//beanType就为definedType
				beanType = definedType;
			}
		}

		// If we have a bean type use it so that generics are considered
		// 如果我们有一个bean类型，请使用它，以便将泛型考虑在内
		//如果拿到了beanType
		if (beanType != null) {
			//返回 beanType是否属于typeToMatch的结果
			return typeToMatch.isAssignableFrom(beanType);
		}

		// If we don't have a bean type, fallback to the predicted type
		// 如果我们没有bean类型，则回退到预测类型
		//如果我们没有bean类型，返回predictedType否属于typeToMatch的结果
		return typeToMatch.isAssignableFrom(predictedType);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, ResolvableType.forRawClass(typeToMatch));
	}

	/**
	 * 确定具有给定名称的bean类型(为了确定其对象类型，默认让FactoryBean以初始化)。
	 *
	 * @param name the name of the bean to query -- 要查询的bean名
	 * @return Bean的类型；如果不确定，则为null
	 * @throws NoSuchBeanDefinitionException 如果没有给定名称的bean
	 * @see #getType(String, boolean)
	 */
	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		return getType(name, true);
	}

	/**
	 * 确定具有给定名称的bean类型。更具体地说，确定getBean将针对给定名称返回的对象 的类型.
	 * <p>逻辑步骤总结：
	 *     <ol>
	 *         <li>获取name对应的规范名称【全类名】,包括以'&'开头的name。然后将结果赋值给变量beanName</li>
	 *         <li>获取beanName注册的单例对象，但不会导致单例对象的创建,如果成功获取到且该单例对象
	 *         又不是NullBean
	 *         		<ol>
	 *         		 	<li>如果bean的单例对象是FactoryBean的实例 且 name不是FactoryBean的解引用名,
	 *         		 		将beanInstance强转为FactoryBean,获取其创建出来的对象的类型并返回</li>
	 *         		 	<li>否则获取beanInstance的类型并返回</li>
	 *         		</ol>
	 *         </li>
	 *         <li>获取该工厂的父级bean工厂,成功获取到了且该bean工厂包含具有beanName的bean定义,
	 *         就从父级bean工厂中获取name的全类名的bean类型并返回，【递归】</li>
	 *         <li>获取beanName对应的合并RootBeanDefinition的Bean定义持有者，通过持有者的合并的RootBeanDefinitio获取所对应bean名的
	 *         最终bean类型，而这类型不属于FactoryBean类型的话就返回出去。</li>
	 *         <li>尝试预测beanName的最终bean类型，如果该类型属于FactoryBean类型：
	 *         		<ol>
	 *         		 <li>如果name不是FactoryBean的解引用名,则尽可能的使用beanName和mbd去获取FactoryBean定义的bean类型。</li>
	 *         		 <li>如果是FactoryBean的解引用，就直接返回该beanName的最终bean类型</li>
	 *         		</ol>
	 *         </li>
	 *         <li>如果没有成功预测到beanName的最终bean类型 或者 最终bean类型不属于FactoryBean类型,
	 *         再加上如果name不是FactoryBean的解引用名,就直接返回该beanName的最终bean类型，否则返回null</li>
	 *     </ol>
	 * </p>
	 *
	 * @param name                 the name of the bean to query -- 要查询的bean名
	 * @param allowFactoryBeanInit whether a {@code FactoryBean} may get initialized
	 *                             just for the purpose of determining its object type
	 *                             -- {@code FactoryBean}是否可以初始化仅仅是为了确定其对象类型
	 * @return
	 * @throws NoSuchBeanDefinitionException
	 */
	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		//获取name对应的规范名称【全类名】,包括以'&'开头的name
		String beanName = transformedBeanName(name);

		// Check manually registered singletons.
		// 检查手动注册的单例
		// 获取beanName注册的单例对象，但不会创建早期引用
		Object beanInstance = getSingleton(beanName, false);
		//如果成功获取到beanName的单例对象，且 该单例对象又不是NullBean,NullBean用于表示null
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			//如果bean的单例对象是FactoryBean的实例 且 name不是FactoryBean的解引用
			if (beanInstance instanceof FactoryBean<?> factoryBean && !BeanFactoryUtils.isFactoryDereference(name)) {
				//将beanInstance强转为FactoryBean,获取其创建出来的对象的类型并返回
				return getTypeForFactoryBean(factoryBean);
			} else {
				//获取beanInstance的类型并返回
				return beanInstance.getClass();
			}
		}

		// No singleton instance found -> check bean definition.
		// 找不到单例实例 -> 检查bean定义
		//获取该工厂的父级bean工厂
		BeanFactory parentBeanFactory = getParentBeanFactory();
		//如果成功获取到了父级bean工厂 且 该bean工厂包含具有beanName的bean定义
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			// 在该工厂中找不到bean定义 -> 委托给父对象
			// originalBeanName:获取name对应的规范名称【全类名】，如果name前面有'&'，则会返回'&'+规范名称【全类名】
			// 从父级bean工厂中获取name的全类名的bean类型，【递归】
			return parentBeanFactory.getType(originalBeanName(name));
		}
		//获取beanName对应的合并RootBeanDefinition，如果bean对应于子bean定义，则遍历父bean定义
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		//尝试预测beanName的最终bean类型
		Class<?> beanClass = predictBeanType(beanName, mbd);

		if (beanClass != null) {
			// Check bean class whether we're dealing with a FactoryBean.
			// Check bean class whether we're dealing with a FactoryBean.
			// 检查bean类是否正在处理FactoryBean
			// 如果成功预测到beanName的最终bean类型 且 该类属于FactoryBean类型
			if (FactoryBean.class.isAssignableFrom(beanClass)) {
				//如果name不是FactoryBean的解引用名
				if (!BeanFactoryUtils.isFactoryDereference(name)) {
					// If it's a FactoryBean, we want to look at what it creates, not at the factory class.
					// If it's a FactoryBean, we want to look at what it creates, not at the factory class.
					// 如果是FactoryBean，则我们要查看它创建的内容，而不是工厂类
					//尽可能的使用beanName和mbd去获取FactoryBean定义的bean类型
					beanClass = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit).resolve();
				}
			} else if (BeanFactoryUtils.isFactoryDereference(name)) {
				return null;
			}
		}

		if (beanClass == null) {
			// Check decorated bean definition, if any: We assume it'll be easier
			// to determine the decorated bean's type than the proxy's type.
			// 检查修饰的bean定义(如果有):我们假设确定修饰的bean类型比代理类型更容易
			// 获取mbd的Bean定义持有者
			BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
			//如果成功获取到mbd的Bean定义持有者 且 name不是FactoryBean的解引用
			// FactoryBean的解引用指的是FactoryBean使用getObject方法得到的对象
			if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
				//获取合并的RootBeanDefinition
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				//尝试预测dbd的bean名的最终bean类型
				Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd);
				//如果成功预测到了bdd的bean名的最终bean类型 且 targetClass不属于FactoryBean类型
				if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
					//返回该预测到的dbd的bean名的最终bean类型
					return targetClass;
				}
			}
		}

		return beanClass;
	}

	/**
	 * 返回给定bean名称的别名(如果有):
	 * <ol>
	 *  <li>去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】【变量 beanName】</li>
	 *  <li>定义用于存放别名的集合【变量 aliases】</li>
	 *  <li>定义保存name是否以'&'开头的结果标记【变量 factoryPrefix】,让有'&'开头时，表示name是一个FactoryBean名</li>
	 *  <li>定义一个完整bean名初始引用beanName【变量 fullBeanName】</li>
	 *  <li>如果name是以'&'开头，完整bean名开头就加上'&'</li>
	 *  <li>如果完整bean名与neam不相同,将fullBeanName添加到aliases中</li>
	 *  <li>获取beanName的所有别名</li>
	 *  <li>遍历所有所有别名
	 *   <ol>
	 *    <li>引用retrievedAlias,如果有'&'前缀,就加上'&'</li>
	 *    <li>如果alias与name不同,将alias添加到aliases中</li>
	 *   </ol>
	 *  </li>
	 *  <li>如果beanName不在该BeanFactory的单例对象的高速缓存Map集合【DefaultListableBeanFactory.singletonObjects】中,
	 *  且 该BeanFactory不包含beanName的BeanDefinition对象:
	 *   <ol>
	 *    <li>获取父工厂</li>
	 *    <li>如果父工厂不为null,使用父工厂获取fullBeanName的所有别名，然后添加到aliases中</li>
	 *   </ol>
	 *  </li>
	 *  <li>将aliases转换成String数组返回出去</li>
	 * </ol>
	 *
	 * @param name the bean name to check for aliases -- 用来检测别名的bena名称
	 * @return 别名，如果没有则为空数组
	 */
	@Override
	public String[] getAliases(String name) {
		//去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】
		String beanName = transformedBeanName(name);
		//定义用于存放别名的集合
		List<String> aliases = new ArrayList<>();
		//定义保存name是否以'&'开头的结果标记,让有'&'开头时，表示name是一个FactoryBean名
		boolean factoryPrefix = name.startsWith(FACTORY_BEAN_PREFIX);
		//定义一个完整bean名初始引用beanName
		String fullBeanName = beanName;
		//如果name是以'&'开头
		if (factoryPrefix) {
			//完整bean名开头就加上'&'
			fullBeanName = FACTORY_BEAN_PREFIX + beanName;
		}
		//如果完整bean名与neam不相同
		if (!fullBeanName.equals(name)) {
			//将fullBeanName添加到aliases中
			aliases.add(fullBeanName);
		}
		//获取beanName的别名
		String[] retrievedAliases = super.getAliases(beanName);
		String prefix = factoryPrefix ? FACTORY_BEAN_PREFIX : "";
		//遍历所有所有别名
		for (String retrievedAlias : retrievedAliases) {
			//引用retrievedAlias,如果有'&'前缀,就加上'&'
			String alias = prefix + retrievedAlias;
			//如果alias与name不同
			if (!alias.equals(name)) {
				//将alias添加到aliases中
				aliases.add(alias);
			}
		}
		//如果beanName不在该BeanFactory的单例对象的高速缓存Map集合【DefaultListableBeanFactory.singletonObjects】中,
		// 且 该BeanFactory不包含beanName的BeanDefinition对象
		if (!containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			//获取父工厂
			BeanFactory parentBeanFactory = getParentBeanFactory();
			//如果父工厂不为null
			if (parentBeanFactory != null) {
				//使用父工厂获取fullBeanName的所有别名，然后添加到aliases中
				aliases.addAll(Arrays.asList(parentBeanFactory.getAliases(fullBeanName)));
			}
		}
		//将aliases转换成String数组返回出去
		return StringUtils.toStringArray(aliases);
	}


	//---------------------------------------------------------------------
	// Implementation of HierarchicalBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	@Nullable
	public BeanFactory getParentBeanFactory() {
		return this.parentBeanFactory;
	}

	/**
	 * <p>本地BeanFactory是否包含给定名称的bean.</p>
	 * <p>满足以下全部条件才认为本地bean工厂包含name的bean:
	 *  <ol>
	 *   <li> beanName存在于该BeanFactory的singletonObjects【单例对象的高速缓存Map集合】中
	 *   或者该BeanFactory包含beanName的BeanDefinition对象</li>
	 *   <li>该BeanFactory包含beanName的BeanDefinition对象</li>
	 *   <li>name为该工厂的FactoryBean的解引用.判断依据：name是以'&'开头，就是FactoryBean的解引用</li>
	 *   <li>beanName是FactoryBean</li>
	 *  </ol>
	 * </p>
	 *
	 * @param name the name of the bean to query -- 要查询的bean的名称
	 * @return 本地BeanFactory是否包含给定名称的bean
	 */
	@Override
	public boolean containsLocalBean(String name) {
		//获取name最终的规范名称【最终别名】
		String beanName = transformedBeanName(name);
		//满足以下全部条件则认为本地bean工厂包含name的bean：
		//1. beanName存在于该BeanFactory的singletonObjects【单例对象的高速缓存Map集合】中
		// 		或者该BeanFactory包含beanName的BeanDefinition对象
		//2. 该BeanFactory包含beanName的BeanDefinition对象
		//3. name为该工厂的FactoryBean的解引用.判断依据：name是以'&'开头，就是FactoryBean的解引用
		//4. beanName是FactoryBean
		return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
				(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void setParentBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		//如果当前已经有一个父级bean工厂，且传进来的父级bean工厂与当前父级bean工厂不是同一个。
		if (this.parentBeanFactory != null && this.parentBeanFactory != parentBeanFactory) {
			//抛出异常
			throw new IllegalStateException("Already associated with parent BeanFactory: " + this.parentBeanFactory);
		}
		if (this == parentBeanFactory) {
			throw new IllegalStateException("Cannot set parent bean factory to self");
		}
		this.parentBeanFactory = parentBeanFactory;
	}

	@Override
	public void setBeanClassLoader(@Nullable ClassLoader beanClassLoader) {
		//默认使用线程上下文类加载器
		this.beanClassLoader = (beanClassLoader != null ? beanClassLoader : ClassUtils.getDefaultClassLoader());
	}

	@Override
	@Nullable
	public ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setTempClassLoader(@Nullable ClassLoader tempClassLoader) {
		this.tempClassLoader = tempClassLoader;
	}

	@Override
	@Nullable
	public ClassLoader getTempClassLoader() {
		return this.tempClassLoader;
	}

	@Override
	public void setCacheBeanMetadata(boolean cacheBeanMetadata) {
		this.cacheBeanMetadata = cacheBeanMetadata;
	}

	@Override
	public boolean isCacheBeanMetadata() {
		return this.cacheBeanMetadata;
	}

	@Override
	public void setBeanExpressionResolver(@Nullable BeanExpressionResolver resolver) {
		this.beanExpressionResolver = resolver;
	}

	@Override
	@Nullable
	public BeanExpressionResolver getBeanExpressionResolver() {
		return this.beanExpressionResolver;
	}

	@Override
	public void setConversionService(@Nullable ConversionService conversionService) {
		this.conversionService = conversionService;
	}

	@Override
	@Nullable
	public ConversionService getConversionService() {
		return this.conversionService;
	}

	@Override
	public void addPropertyEditorRegistrar(PropertyEditorRegistrar registrar) {
		Assert.notNull(registrar, "PropertyEditorRegistrar must not be null");
		this.propertyEditorRegistrars.add(registrar);
	}

	/**
	 * Return the set of PropertyEditorRegistrars.
	 */
	public Set<PropertyEditorRegistrar> getPropertyEditorRegistrars() {
		return this.propertyEditorRegistrars;
	}

	@Override
	public void registerCustomEditor(Class<?> requiredType, Class<? extends PropertyEditor> propertyEditorClass) {
		Assert.notNull(requiredType, "Required type must not be null");
		Assert.notNull(propertyEditorClass, "PropertyEditor class must not be null");
		this.customEditors.put(requiredType, propertyEditorClass);
	}

	@Override
	public void copyRegisteredEditorsTo(PropertyEditorRegistry registry) {
		registerCustomEditors(registry);
	}

	/**
	 * Return the map of custom editors, with Classes as keys and PropertyEditor classes as values.
	 */
	public Map<Class<?>, Class<? extends PropertyEditor>> getCustomEditors() {
		return this.customEditors;
	}

	@Override
	public void setTypeConverter(TypeConverter typeConverter) {
		this.typeConverter = typeConverter;
	}

	/**
	 * Return the custom TypeConverter to use, if any.
	 * <p>返回自定义的TypeConverter以使用(如果有)</p>
	 *
	 * @return the custom TypeConverter, or {@code null} if none specified
	 * -- 自定义TypeConverter，如果未指定，则为null
	 */
	@Nullable
	protected TypeConverter getCustomTypeConverter() {
		return this.typeConverter;
	}

	/**
	 * 获取此BeanFactory使用的类型转换器。这可能是每次调用都有新实例，因TypeConverters通常 不是线程安全的.
	 * <ol>
	 *  <li>获取自定义的TypeConverter【{@link #getCustomTypeConverter()}】【变量 customConverter】</li>
	 *  <li>如果customConverter,返回该customConverter</li>
	 *  <li>新建一个SimpleTypeConverter对象【变量 typeConverter】</li>
	 *  <li>让typeConverter引用该工厂的类型转换的服务接口【{@link #getConversionService()}】</li>
	 *  <li>将工厂中所有PropertyEditor注册到typeConverter中</li>
	 *  <li>返回SimpleTypeConverter作为该工厂的默认类型转换器。</li>
	 * </ol>
	 *
	 * @return 此BeanFactory使用的类型转换器:默认情况下优先返回自定义的类型转换器【{@link #getCustomTypeConverter()}】;
	 * 获取不到时,返回一个新的SimpleTypeConverter对象
	 */
	@Override
	public TypeConverter getTypeConverter() {
		//获取自定义的TypeConverter
		TypeConverter customConverter = getCustomTypeConverter();
		//如果有自定义的TypeConverter
		if (customConverter != null) {
			//返回该自定义的TypeConverter
			return customConverter;
		} else {
			// Build default TypeConverter, registering custom editors.
			// 构建默认的TypeConverter，注册自定义编辑器
			//SimpleTypeConverter:不在特定目标对象上运行的TypeConverter接口的简单实现。
			// 	这是使用完整的BeanWrapperImpl实例来实现 任意类型转换需求的替代方法，同时
			// 	使用相同的转换算法（包括委托给PropertyEditor和ConversionService）。
			//每次调用该方法都会新建一个类型转换器，因为SimpleTypeConverter不是线程安全的
			//新建一个SimpleTypeConverter对象
			SimpleTypeConverter typeConverter = new SimpleTypeConverter();
			//让typeConverter引用该工厂的类型转换的服务接口
			typeConverter.setConversionService(getConversionService());
			//将工厂中所有PropertyEditor注册到typeConverter中
			registerCustomEditors(typeConverter);
			//返回SimpleTypeConverter作为该工厂的默认类型转换器。
			return typeConverter;
		}
	}

	@Override
	public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
		//如果valueResolver为null，抛出异常
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		//将 valueResolver 添加到 embeddedValueResolvers 中
		this.embeddedValueResolvers.add(valueResolver);
	}

	@Override
	public boolean hasEmbeddedValueResolver() {
		// 返回 embeddedValueResolvers是否为空集的结果
		return !this.embeddedValueResolvers.isEmpty();
	}

	/**
	 * 解析嵌套的值(如果value是表达式会解析出该表达式的值)
	 * <ol>
	 *  <li>如果value为null，返回null</li>
	 *  <li>定义返回结果，默认引用value【变量 result】</li>
	 *  <li>遍历该工厂的所有字符串解析器【{@link #embeddedValueResolvers}】：
	 *   <ol>
	 *    <li>解析result，将解析后的值重新赋值给result</li>
	 *    <li>如果result为null,结束该循环并返回null</li>
	 *   </ol>
	 *  </li>
	 *  <li>将解析后的结果返回出去</li>
	 * </ol>
	 *
	 * @param value the value to resolve -- 要解析的值，
	 * @return 解析后的值(可能是原始值)
	 */
	@Override
	@Nullable
	public String resolveEmbeddedValue(@Nullable String value) {
		//如果value为null，返回null
		if (value == null) {
			return null;
		}
		//定义返回结果，默认引用value
		String result = value;
		//SpringBoot默认存放一个PropertySourcesPlaceholderConfigurer，该类注意用于针对当前
		// 	Spring Environment 及其PropertySource解析bean定义属性值和@Value注释中的${...}占位符
		//遍历该工厂的所有字符串解析器
		for (StringValueResolver resolver : this.embeddedValueResolvers) {
			//解析result，将解析后的值重新赋值给result
			result = resolver.resolveStringValue(result);
			//如果result为null,结束该循环，并返回null
			if (result == null) {
				return null;
			}
		}
		//将解析后的结果返回出去
		return result;
	}

	@Override
	public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
		//如果beanPostProcess为null，抛出异常
		Assert.notNull(beanPostProcessor, "BeanPostProcessor must not be null");
		synchronized (this.beanPostProcessors) {
			// Remove from old position, if any
			// 如果有的话，从旧的位置上移除
			//删除在beanPostProcessors中与beanPostProcessor相等的对象
			this.beanPostProcessors.remove(beanPostProcessor);
			// Add to end of list
			// 添加到列表的末尾
			this.beanPostProcessors.add(beanPostProcessor);
		}
	}

	/**
	 * Add new BeanPostProcessors that will get applied to beans created
	 * by this factory. To be invoked during factory configuration.
	 *
	 * @see #addBeanPostProcessor
	 * @since 5.3
	 */
	public void addBeanPostProcessors(Collection<? extends BeanPostProcessor> beanPostProcessors) {
		synchronized (this.beanPostProcessors) {
			// Remove from old position, if any
			this.beanPostProcessors.removeAll(beanPostProcessors);
			// Add to end of list
			this.beanPostProcessors.addAll(beanPostProcessors);
		}
	}

	@Override
	public int getBeanPostProcessorCount() {
		return this.beanPostProcessors.size();
	}

	/**
	 * Return the list of BeanPostProcessors that will get applied
	 * to beans created with this factory.
	 * <p>返回将应用于该工厂创建的bean的BeanPostProcessors列表</p>
	 */
	public List<BeanPostProcessor> getBeanPostProcessors() {
		return this.beanPostProcessors;
	}

	/**
	 * Return the internal cache of pre-filtered post-processors,
	 * freshly (re-)building it if necessary.
	 *
	 * @since 5.3
	 */
	BeanPostProcessorCache getBeanPostProcessorCache() {
		synchronized (this.beanPostProcessors) {
			BeanPostProcessorCache bppCache = this.beanPostProcessorCache;
			if (bppCache == null) {
				bppCache = new BeanPostProcessorCache();
				for (BeanPostProcessor bpp : this.beanPostProcessors) {
					if (bpp instanceof InstantiationAwareBeanPostProcessor instantiationAwareBpp) {
						bppCache.instantiationAware.add(instantiationAwareBpp);
						if (bpp instanceof SmartInstantiationAwareBeanPostProcessor smartInstantiationAwareBpp) {
							bppCache.smartInstantiationAware.add(smartInstantiationAwareBpp);
						}
					}
					if (bpp instanceof DestructionAwareBeanPostProcessor destructionAwareBpp) {
						bppCache.destructionAware.add(destructionAwareBpp);
					}
					if (bpp instanceof MergedBeanDefinitionPostProcessor mergedBeanDefBpp) {
						bppCache.mergedDefinition.add(mergedBeanDefBpp);
					}
				}
				this.beanPostProcessorCache = bppCache;
			}
			return bppCache;
		}
	}

	private void resetBeanPostProcessorCache() {
		synchronized (this.beanPostProcessors) {
			this.beanPostProcessorCache = null;
		}
	}

	/**
	 * Return whether this factory holds a InstantiationAwareBeanPostProcessor
	 * that will get applied to singleton beans on creation.
	 * <p>指示是否已经注册了任何 InstantiationAwareBeanPostProcessors 对象
	 *
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
	 */
	protected boolean hasInstantiationAwareBeanPostProcessors() {
		return !getBeanPostProcessorCache().instantiationAware.isEmpty();
	}

	/**
	 * Return whether this factory holds a DestructionAwareBeanPostProcessor
	 * that will get applied to singleton beans on shutdown.
	 * <p>表明是否注册了任何 DestructionAwareBeanPostProcessor
	 *
	 * @see #addBeanPostProcessor
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean hasDestructionAwareBeanPostProcessors() {
		return !getBeanPostProcessorCache().destructionAware.isEmpty();
	}

	@Override
	public void registerScope(String scopeName, Scope scope) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
		Assert.notNull(scope, "Scope must not be null");
		if (SCOPE_SINGLETON.equals(scopeName) || SCOPE_PROTOTYPE.equals(scopeName)) {
			throw new IllegalArgumentException("Cannot replace existing scopes 'singleton' and 'prototype'");
		}
		Scope previous = this.scopes.put(scopeName, scope);
		if (previous != null && previous != scope) {
			if (logger.isDebugEnabled()) {
				logger.debug("Replacing scope '" + scopeName + "' from [" + previous + "] to [" + scope + "]");
			}
		} else {
			if (logger.isTraceEnabled()) {
				logger.trace("Registering scope '" + scopeName + "' with implementation [" + scope + "]");
			}
		}
	}

	@Override
	public String[] getRegisteredScopeNames() {
		return StringUtils.toStringArray(this.scopes.keySet());
	}

	/**
	 * 获取给定作用域名称对应的作用域对象（如果有）:
	 * <ol>
	 *     <li>如果scopeName为null，抛出异常</li>
	 *     <li>从映射的linkedHashMap【scopes】中获取scopeName对应的作用域对象并返回</li>
	 * </ol>
	 *
	 * @param scopeName the name of the scope -- 作用域名
	 * @return scopeName对应的作用域对象
	 */
	@Override
	@Nullable
	public Scope getRegisteredScope(String scopeName) {
		//如果传入的作用域名为null，抛出异常
		Assert.notNull(scopeName, "Scope identifier must not be null");
		//从映射的linkedHashMap中获取传入的作用域名对应的作用域对象并返回
		return this.scopes.get(scopeName);
	}

	@Override
	public void setApplicationStartup(ApplicationStartup applicationStartup) {
		Assert.notNull(applicationStartup, "applicationStartup must not be null");
		this.applicationStartup = applicationStartup;
	}

	@Override
	public ApplicationStartup getApplicationStartup() {
		return this.applicationStartup;
	}

	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		Assert.notNull(otherFactory, "BeanFactory must not be null");
		setBeanClassLoader(otherFactory.getBeanClassLoader());
		setCacheBeanMetadata(otherFactory.isCacheBeanMetadata());
		setBeanExpressionResolver(otherFactory.getBeanExpressionResolver());
		setConversionService(otherFactory.getConversionService());
		if (otherFactory instanceof AbstractBeanFactory otherAbstractFactory) {
			this.propertyEditorRegistrars.addAll(otherAbstractFactory.propertyEditorRegistrars);
			this.customEditors.putAll(otherAbstractFactory.customEditors);
			this.typeConverter = otherAbstractFactory.typeConverter;
			this.beanPostProcessors.addAll(otherAbstractFactory.beanPostProcessors);
			this.scopes.putAll(otherAbstractFactory.scopes);
		} else {
			setTypeConverter(otherFactory.getTypeConverter());
			String[] otherScopeNames = otherFactory.getRegisteredScopeNames();
			for (String scopeName : otherScopeNames) {
				this.scopes.put(scopeName, otherFactory.getRegisteredScope(scopeName));
			}
		}
	}

	/**
	 * Return a 'merged' BeanDefinition for the given bean name,
	 * merging a child bean definition with its parent if necessary.
	 * <p>This {@code getMergedBeanDefinition} considers bean definition
	 * in ancestors as well.
	 * 返回给定 bean 名称的"合并"Bean 定义，必要时将子 bean 定义与父级合并。
	 * 如果在当前 BeanFactory 中找不到给定名称的bean定义，将会考虑从父工厂中查找bean定义
	 *
	 * @param name the name of the bean to retrieve the merged definition for
	 *             (may be an alias)
	 *             用于检索的合并bean定义的 bean 的名称（可能是别名）
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * 一个已经合并完毕(如果存在父bean)的RootBeanDefinition类型的bean定义
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 *                                       如果没有给定名称的 bean
	 * @throws BeanDefinitionStoreException  in case of an invalid bean definition
	 *                                       无效的 bean 定义
	 */
	@Override
	public BeanDefinition getMergedBeanDefinition(String name) throws BeansException {
		//返回 bean 名称，必要时删除&引用前缀，并解析别名为规范名称。
		String beanName = transformedBeanName(name);
		// Efficiently check whether bean definition exists in this factory.
		// 如果在当前 BeanFactory 中找不到给定名称的bean定义，将会考虑从父工厂中查找bean定义
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory parent) {
			//从父工厂中查找并且合并bean定义
			return parent.getMergedBeanDefinition(beanName);
		}
		// Resolve merged bean definition locally.
		// 否则，在本地工厂解析合并的 bean 定义。
		return getMergedLocalBeanDefinition(beanName);
	}

	/**
	 * 确定name的Bean是否为FactoryBean：
	 * <ol>
	 *  <li>去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】【变量 beanName】</li>
	 *  <li><b>【尝试获取Bean对象来检查】</b>
	 *   <ol>
	 *    <li>获取beanName注册的（原始）单例对象，如果单例对象没有找到，并且beanName存在正在创建的Set
	 *    集合中</li>
	 *    <li>如果beanInstance能获取到,如果 beanInstance是FactoryBean的实例 则返回true，
	 *    否则返回false。</li>
	 *   </ol>
	 *  </li>
	 *  <li><b>【检查beanDefinition】:</b>
	 *   <ol>
	 *    <li>如果该bean工厂不包含beanName的bean定义【不考虑工厂可能参与的任何层次结构】且该工厂的
	 *    父工厂是ConfigurableBeanFactory的实例,尝试在父工厂中确定name是否为FactoryBean，
	 *    如果不是返回false，否则返回true【递归】</li>
	 *    <li>判断(beanName和beanName对应的合并后BeanDefinition)所指的bean是否FactoryBean
	 *    并将结果返回出去</li>
	 *   </ol>
	 *  </li>
	 * </ol>
	 *
	 * @param name the name of the bean to check - 要检查的bean名称
	 * @return bean是否为FactoryBean(false表示该bean存在, 但不是FactoryBean)
	 * @throws NoSuchBeanDefinitionException 如果没有给定名称的bean
	 */
	@Override
	public boolean isFactoryBean(String name) throws NoSuchBeanDefinitionException {
		//去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】：
		String beanName = transformedBeanName(name);
		//获取beanName注册的（原始）单例对象，如果单例对象没有找到，并且beanName存在
		Object beanInstance = getSingleton(beanName, false);
		//如果已经是单例，就判断是否是FactoryBean类型
		if (beanInstance != null) {
			return (beanInstance instanceof FactoryBean);
		}
		// No singleton instance found -> check bean definition.
		// 找不到单例实例 -> 检查bean定义
		// 如果该bean工厂不包含beanName的bean定义【不考虑工厂可能参与的任何层次结构】 且
		// 		该工厂的父工厂是ConfigurableBeanFactory的实例
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory cbf) {
			// No bean definition found in this factory -> delegate to parent.
			// 在该工厂中找不到bean定义 -> 委托给父对象
			// 尝试在父工厂中确定name是否为FactoryBean，如果不是返回false，否则返回true【递归】
			return cbf.isFactoryBean(name);
		}
		//getMergedLocalBeanDefinition:获取beanName对应合并的RootBeanDefinition,
		// 		如果该bean对应于子bean定义，则遍历父bean定义。
		//判断(beanName和beanName对应的合并后BeanDefinition)所指的bean是否FactoryBean并将结果返回出去
		return isFactoryBean(beanName, getMergedLocalBeanDefinition(beanName));
	}

	/**
	 * 是否实际上正在创建
	 * <p>
	 * 如果beanName是单例，且当前正在创建（在整个工厂内） 或者 如果beanName是否原型，且当前正在创建中（在当前线程内）都会
	 * 返回true
	 * </p>
	 *
	 * @param beanName bean名
	 */
	@Override
	public boolean isActuallyInCreation(String beanName) {
		//如果beanName是单例，且当前正在创建（在整个工厂内） 或者 如果beanName是否原型，且当前正在创建中（在当前线程内）都会 返回true
		return (isSingletonCurrentlyInCreation(beanName) || isPrototypeCurrentlyInCreation(beanName));
	}

	/**
	 * Return whether the specified prototype bean is currently in creation
	 * (within the current thread).
	 * <p>返回指定的原型bean是否当前正在创建中（在当前线程内）</p>
	 *
	 * @param beanName the name of the bean - bean名
	 */
	protected boolean isPrototypeCurrentlyInCreation(String beanName) {
		//获取当前正在创建的bean名称【线程本地】
		Object curVal = this.prototypesCurrentlyInCreation.get();
		//如果当前正在创建的bean名称不为null，且 （当前正在创建的bean名称等于beanName 或者
		//	 当前正在创建的bean名称是Set集合，并包含该beanName）
		//就返回true，表示在当前线程内，beanName当前正在创建中。
		return (curVal != null &&
				(curVal.equals(beanName) || (curVal instanceof Set<?> set && set.contains(beanName))));
	}

	/**
	 * <p>创建ProtoPype对象前的准备工作，默认实现 将beanName添加到 prototypesCurrentlyInCreation 中</p>
	 * Callback before prototype creation.
	 * <p>原型创建前的回调</p>
	 * <p>The default implementation register the prototype as currently in creation.
	 * <p>默认实现将原型注册为当前创建中</p>
	 *
	 * @param beanName the name of the prototype about to be created
	 *                 -- 将要创建的原型的名称
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void beforePrototypeCreation(String beanName) {
		//获取当前线程正在创建的原型beanName
		Object curVal = this.prototypesCurrentlyInCreation.get();
		/*如果curVal为null*/
		if (curVal == null) {
			//那么设置值为当前beanName
			this.prototypesCurrentlyInCreation.set(beanName);
		}
		/*
		 * 否则，如果curVal属于String，那么转换为set集合用于存放多个beanName
		 */
		else if (curVal instanceof String strValue) {
			//新建set集合
			Set<String> beanNameSet = new HashSet<>(2);
			//添加原值
			beanNameSet.add(strValue);
			//添加新值
			beanNameSet.add(beanName);
			//值设置为该集合
			this.prototypesCurrentlyInCreation.set(beanNameSet);
		}
		/*
		 * 否则，如果表示curVal属于set集合
		 */
		else {
			//强制转型
			Set<String> beanNameSet = (Set<String>) curVal;
			//添加新值
			beanNameSet.add(beanName);
		}
	}

	/**
	 * <p>创建完prototype实例后的回调，默认是将beanName从 prototypesCurrentlyInCreation 移除</p>
	 * Callback after prototype creation.
	 * <p>原型创建后的回调</p>
	 * <p>The default implementation marks the prototype as not in creation anymore.
	 * <p>默认实现将原型标记为不在创建中</p>
	 *
	 * @param beanName the name of the prototype that has been created
	 *                 -- 已创建的原型的名称
	 * @see #isPrototypeCurrentlyInCreation
	 */
	@SuppressWarnings("unchecked")
	protected void afterPrototypeCreation(String beanName) {
		//获取当前线程正在创建的原型beanName
		Object curVal = this.prototypesCurrentlyInCreation.get();
		/*
		 * 如果curVal属于String
		 */
		if (curVal instanceof String) {
			//直接移除当前线程的ThreadLocal键值对即可
			this.prototypesCurrentlyInCreation.remove();
		}
		/*
		 * 否则，如果表示curVal属于set集合
		 */
		else if (curVal instanceof Set<?> beanNameSet) {
			//从集合中移除该beanName
			beanNameSet.remove(beanName);
			//如果移除之后集合为为空，那么直接移除当前线程的ThreadLocal键值对
			if (beanNameSet.isEmpty()) {
				this.prototypesCurrentlyInCreation.remove();
			}
		}
	}

	@Override
	public void destroyBean(String beanName, Object beanInstance) {
		destroyBean(beanName, beanInstance, getMergedLocalBeanDefinition(beanName));
	}

	/**
	 * Destroy the given bean instance (usually a prototype instance
	 * obtained from this factory) according to the given bean definition.
	 *
	 * @param beanName the name of the bean definition
	 * @param bean     the bean instance to destroy
	 * @param mbd      the merged bean definition
	 */
	protected void destroyBean(String beanName, Object bean, RootBeanDefinition mbd) {
		new DisposableBeanAdapter(
				bean, beanName, mbd, getBeanPostProcessorCache().destructionAware).destroy();
	}

	@Override
	public void destroyScopedBean(String beanName) {
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		if (mbd.isSingleton() || mbd.isPrototype()) {
			throw new IllegalArgumentException(
					"Bean name '" + beanName + "' does not correspond to an object in a mutable scope");
		}
		String scopeName = mbd.getScope();
		Scope scope = this.scopes.get(scopeName);
		if (scope == null) {
			throw new IllegalStateException("No Scope SPI registered for scope name '" + scopeName + "'");
		}
		Object bean = scope.remove(beanName);
		if (bean != null) {
			destroyBean(beanName, bean, mbd);
		}
	}


	//---------------------------------------------------------------------
	// Implementation methods
	//---------------------------------------------------------------------

	/**
	 * <p>去除name开头的'&'字符,获取name最终的规范名称【最终别名或者是全类名】：去除开头的'&'字符，返回剩余的字符串得到转换后的Bean名称,
	 * 然后通过递归形式在 aliasMap【别名映射到规范名称集合】中得到最终的规范名称
	 * </p>
	 * Return the bean name, stripping out the factory dereference prefix if necessary,
	 * and resolving aliases to canonical names.
	 * <p>返回Bean名称，如果必要，去除工厂取消前缀，并将别名解析为规范名称</p>
	 *
	 * @param name the user-specified name - 用户指定的名称
	 * @return the transformed bean name - 转换后的bena名称
	 */
	protected String transformedBeanName(String name) {

		//去除开头的'&'字符，返回剩余的字符串得到转换后的Bean名称，然后通过递归形式在
		// aliasMap【别名映射到规范名称集合】中得到最终的规范名称
		return canonicalName(BeanFactoryUtils.transformedBeanName(name));
	}

	/**
	 * <p>获取name对应的规范名称【全类名/最终别名】，如果name前面有'&'，则会返回'&'+规范名称【全类名】</p>
	 * Determine the original bean name, resolving locally defined aliases to canonical names.
	 * <p>确定原始bean名，将本地定义的别名解析为规范名称</p>
	 *
	 * @param name the user-specified name - 用户指定的名称
	 * @return the original bean name 原始bena名
	 */
	protected String originalBeanName(String name) {
		//调用transformedBeanName方法，必要时删除全部&引用前缀，并解析别名为规范名称。
		String beanName = transformedBeanName(name);
		//name是以&为前缀
		if (name.startsWith(FACTORY_BEAN_PREFIX)) {
			//那么解析之后的规范名称同样加上&前缀
			beanName = FACTORY_BEAN_PREFIX + beanName;
		}
		return beanName;
	}

	/**
	 * <p>
	 * 初始化BeanWrapper：
	 *  <ol>
	 *   <li>使用该工厂的ConversionService来作为bw的ConverisonService，用于转换属性值，以替换JavaBeans PropertyEditor</li>
	 *   <li>将PropertyEditor注册到bw中</li>
	 *  </ol>
	 * </p>
	 * Initialize the given BeanWrapper with the custom editors registered
	 * with this factory. To be called for BeanWrappers that will create
	 * and populate bean instances.
	 * <p>使用在此工厂注册的自定义编辑器初始化给定的BeanWrapper。被BeanWrappers调用，
	 * 它将创建并填充Bean实例</p>
	 * <p>The default implementation delegates to {@link #registerCustomEditors}.
	 * Can be overridden in subclasses.
	 * <p>默认实现委派registerCustomEditor。可以在子类中覆盖</p>
	 *
	 * @param bw the BeanWrapper to initialize -- 要初始化的BeanWrapper
	 */
	protected void initBeanWrapper(BeanWrapper bw) {
		//获取AbstractBeanFactory的conversionService属性值设置给BeanWrapper的父类PropertyEditorRegistrySupport的conversionService属性
		//将会使用Spring 3.0 引入的用于转换属性值的转换服务，作为 JavaBeans PropertyEditors（属性编辑器）的替代服务
		//普通spring项目不会自动注册ConversionService bean，因此默认基于PropertyEditor。boot项目默认自动注册ApplicationConversionService服务
		bw.setConversionService(getConversionService());
		//使用已在此 BeanFactory 注册的自定义编辑器初始化给定的PropertyEditorRegistry中，即初始化bw
		registerCustomEditors(bw);
	}

	/**
	 * <p>将工厂中所有PropertyEditor注册到PropertyEditorRegistry中：
	 *  <ol>
	 *   <li>将registry强转成PropertyEditorRegistrySupport对象，如果registry不能强转则为null【变量registrySupport】</li>
	 *   <li>如果成功获取registrySupport,就激活仅用于配置目的的配置值编辑器，例如：StringArrayPropertyEditor.</li>
	 *   <li>如果该工厂的propertyEditorRegistry列表【propertyEditorRegistrars】不为空
	 *    <ol>
	 *     <li>遍历propertyEditorRegistrars,元素为registrar：
	 *      <ol>
	 *       <li>将registrar中的所有PropertyEditor注册到PropertyEditorRegistry中</li>
	 *       <li>捕捉Bean创建异常【变量ex】:
	 *        <ol>
	 *         <li>获取ex中最具体的原因【变量 rootCause】</li>
	 *         <li>如果是因为当前正在创建Bean异常:
	 *          <ol>
	 *           <li>将rootCause强转成BeanCreationException【变量 bce】</li>
	 *           <li>获取发生异常的bean名【变量 bceBeanName】</li>
	 *           <li>如果bean名不为null 且 是该bean名正在被创建:
	 *            <ol>
	 *             <li>如果是日志级别是调试级别,打印调试日志</li>
	 *             <li>将要注册的异常对象添加到 抑制异常列表【suppressedExceptions】 中</li>
	 *             <li>不再抛出异常,继续循环</li>
	 *            </ol>
	 *           </li>
	 *           <li>重写抛出ex</li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *     <li>如果该工厂的自定义PropertyEditor集合【customEditors】有元素:遍历customEditors,将其注册到registry中
	 *     </li>
	 *    </ol>
	 *   </li>
	 *  </ol>
	 * </p>
	 * Initialize the given PropertyEditorRegistry with the custom editors
	 * that have been registered with this BeanFactory.
	 * <p>使用已在此BeanFactory中注册的自定义编辑器初始化给定的PropertyEditorRegistry</p>
	 * <p>To be called for BeanWrappers that will create and populate bean
	 * instances, and for SimpleTypeConverter used for constructor argument
	 * and factory method type conversion.
	 * <p>对于将创建和填充bean实例的BeanWrappers以及用于构造函数参数和工厂方法类型转换
	 * 的SimpleTypeConverter调用</p>
	 *
	 * @param registry the PropertyEditorRegistry to initialize -- 要进行初始化的PropertyEditorRegistry
	 */
	protected void registerCustomEditors(PropertyEditorRegistry registry) {
		//转换为PropertyEditorRegistrySupport类型，我们前面讲过，BeanWrapperImpl算作PropertyEditorRegistrySupport的子类
		PropertyEditorRegistrySupport registrySupport =
				(registry instanceof PropertyEditorRegistrySupport ? (PropertyEditorRegistrySupport) registry : null);
		//如果不为null
		if (registrySupport != null) {
			//将PropertyEditorRegistrySupport的configValueEditorsActive属性设置为true，即激活仅用于配置目的的配置值编辑器，比如StringArrayPropertyEditor
			//默认情况下，这些编辑器不会被注册，因为它们通常不适合数据绑定目的。当然，在任何情况下，都可以调用registerCustomEditor手动注册
			registrySupport.useConfigValueEditors();
		}
		/*
		 * 1 如果AbstractBeanFactory的propertyEditorRegistrars缓存不为空，即存在自定义属性编辑器注册表，那么需要将这些注册表中的编辑器注册到给定的registry中
		 *
		 * 在前面讲过的prepareBeanFactory方法中，就向propertyEditorRegistrars缓存中注册了一个ResourceEditorRegistrar的注册表实例
		 * customEditors默认就是只有一个注解表，就是ResourceEditorRegistrar。我们在扩展Spring的时候，比如扩展prepareBeanFactory方法
		 * 更常见的是实现BeanFactoryPostProcessor接口，可以调用 beanFactory.addPropertyEditorRegistrar方法注册自定义的属性编辑器注册表到propertyEditorRegistrars缓存中
		 * Spring提供了一个专门配置自定义属性编辑器的BeanFactoryPostProcessor接口实现CustomEditorConfigurer，我们直接使用即可
		 */
		if (!this.propertyEditorRegistrars.isEmpty()) {
			//遍历注册表
			for (PropertyEditorRegistrar registrar : this.propertyEditorRegistrars) {
				try {
					//将注册表中的全部自定义的编辑器注册到给定的registry的缓存中
					//实际上是存入PropertyEditorRegistrySupport的overriddenDefaultEditors缓存中
					registrar.registerCustomEditors(registry);
				} catch (BeanCreationException ex) {
					Throwable rootCause = ex.getMostSpecificCause();
					if (rootCause instanceof BeanCurrentlyInCreationException) {
						BeanCreationException bce = (BeanCreationException) rootCause;
						String bceBeanName = bce.getBeanName();
						if (bceBeanName != null && isCurrentlyInCreation(bceBeanName)) {
							if (logger.isDebugEnabled()) {
								logger.debug("PropertyEditorRegistrar [" + registrar.getClass().getName() +
										"] failed because it tried to obtain currently created bean '" +
										ex.getBeanName() + "': " + ex.getMessage());
							}
							onSuppressedException(ex);
							continue;
						}
					}
					throw ex;
				}
			}
		}
		/*
		 * 2 如果AbstractBeanFactory的customEditors缓存不为空，即存在自定义属性编辑器，那么需要将这些自定义的编辑器注册到给定的registry中
		 *
		 * customEditors默认就是空集合，我们在扩展Spring的时候，比如扩展prepareBeanFactory方法，比如BeanFactoryPostProcessor接口
		 * 更常见的是实现BeanFactoryPostProcessor接口，可以调用 beanFactory.registerCustomEditor方法注册自定义的属性编辑器到customEditors缓存中
		 * Spring提供了一个专门配置自定义属性编辑器的BeanFactoryPostProcessor接口实现CustomEditorConfigurer，我们直接使用即可
		 */
		if (!this.customEditors.isEmpty()) {
			//遍历该集合
			this.customEditors.forEach((requiredType, editorClass) ->
					//将自定义的编辑器注册到给定的registry的缓存中
					//实际上是存入PropertyEditorRegistrySupport的customEditors缓存中
					registry.registerCustomEditor(requiredType, BeanUtils.instantiateClass(editorClass)));
		}
	}

	/**
	 * <p>获取beanName合并后的本地RootBeanDefintiond:
	 * <ol>
	 *     <li>优先从合并BeanDefinitionMap缓存【mergedBeanDefinitions】中获取，获取到的
	 *     RootBeanDefinition对象只要没有标记为重新合并定义的，都会返回出去</li>
	 *     <li>获取beanName在该工厂所指的BeanDefinition对象,传入getMergedBeanDefinition(beanName,BeanDefinition)
	 *     方法，将该方法的返回结果返回出去</li>
	 * </ol>
	 * </p>
	 * Return a merged RootBeanDefinition, traversing the parent bean definition
	 * if the specified bean corresponds to a child bean definition.
	 * <p>返回合并的RootBeanDefinition,如果指定的bean对应于子bean定义，则遍历父bean定义。</p>
	 *
	 * @param beanName the name of the bean to retrieve the merged definition for
	 *                 -- 用于检索其合并定义的bean名
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * -- 结合bean的(可能合并的）RootBeanDefinition
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 *                                       -- 如果某给定名称的bean
	 * @throws BeanDefinitionStoreException  in case of an invalid bean definition
	 *                                       -- 如果bena定义无效
	 */
	protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) throws BeansException {
		// Quick check on the concurrent map first, with minimal locking.
		//检查beanName 对应的 mergedBeanDefinitions是否存在于缓存中，次缓存在 BeanFactoryPostProcessor 中添加
		RootBeanDefinition mbd = this.mergedBeanDefinitions.get(beanName);
		//如果mbd不为null，且stale为false不需要重新合并，那么直接返回mbd
		if (mbd != null && !mbd.stale) {
			return mbd;
		}
		//否则重新合并bean的定义并且返回，传递beanName以及当前工厂中给定beanName的bean定义
		return getMergedBeanDefinition(beanName, getBeanDefinition(beanName));
	}

	/**
	 * <p>获取beanName对应的合并后的RootBeanDefinition:直接交给
	 * getMergedBeanDefinition(String, BeanDefinition,BeanDefinition)处理，第三个参数
	 * 传null</p>
	 * Return a RootBeanDefinition for the given top-level bean, by merging with
	 * the parent if the given bean's definition is a child bean definition.
	 * <p>如果给定RootBeanDefinition是子BeanDefinition，则通过与父级合并返回
	 * RootBeanDefinition</p>
	 *
	 * @param beanName the name of the bean definition -bean定义名称
	 * @param bd       the original bean definition (Root/ChildBeanDefinition)
	 *                 -- 原始BeanDefinition(Root/ChildBeanDefinition)
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * -- 给定bean的（可能合并的）RootBeanDefinition
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 *                                      -- 如果Bean定义无效
	 */
	protected RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition bd)
			throws BeanDefinitionStoreException {

		return getMergedBeanDefinition(beanName, bd, null);
	}

	/**
	 * <p>
	 * 获取beanName对应的合并后的RootBeanDefinition:
	 *     <ol>
	 *         <li>该方法时线程安全的，使用 合并后的RootBeanDefinitionMap缓存 【mergedBeanDefinitions】进行加锁</li>
	 *         <li>尝试从mergedBeanDefinitions中获取beanName合并后的RootBeanDefinition,【mdb】。如果mbd为null
	 *         或者 mbd需要重新合并定义,（在mdb需要重新合并定义情况下，会对mdb赋值到【pervious】，用于来与新的mbd进行合并）
	 *         会通过下面的方式尽可能的获取或构造出mbd，也就是说mbd就是合并后的RootBeanDefinition：
	 *     		 <ol>
	 *         		 	<li>在bd没有父级Bean名下
	 *         		 	<ol>
	 *         		 	  <li>如果bd是RootBeanDefinition,mdb就是bd的副本</li>
	 *         		 	  <li>如果bd不是RootBeanDefinition,就对bd的属性进行深度克隆到一个新的RootBeabDefinition
	 *         		 	  再赋值给mdb</li>
	 *         		 	</ol>
	 *         		 </li>
	 *         		<li>如果bd有父级Bean名：
	 *         			<ol>
	 *         			 <li>获取bd的父级Bean对应的最终别名【parentBeanName】
	 *         			 	<ol>
	 *         			 	  <li>如果beanName不等于parentBeanName,就获取parentBeanName合并后的BeanDefinition【pdb】</li>
	 *         			 	  <li>否则,就会获取该工厂的父工厂：
	 *         			 	  	<ol>
	 *         			 	  	   <li>如果父工厂是 ConfigurableBeanFactory的实例,使用父工厂获取parentBeanName
	 *         			 	  	   合并后的BeanDefinition赋值给pdb</li>
	 *         			 	  	   <li>否则抛出NoSuchBeanDefinitionException</li>
	 *         			 	  	</ol>
	 *         			 	  </li>
	 *         			 	</ol>
	 *         			 </li>
	 *         			 <li>就对pbd的属性进行深度克隆到一个新的RootBeabDefinitiond复制给mdb</li>
	 *         			 <li>使用bd的信息覆盖mbd</li>
	 *         			</ol>
	 *         		</li>
	 *         		 </ol>
	 *         </li>
	 *         <li>如果mbd没有设置作用域，会对mbd设置默认作用域，默认是单例</li>
	 *         <li>如果传入了contatiningBd，就以contatingBd的作用域作为mbd的作用域</li>
	 *         <li>如果没有传入containingBd且当前工厂是同意缓存bean元数据，就会将beanName和mbd的映射关系添加到
	 *         合并后的RootBeanDefinitionMap缓存中【mergedBeanDefinitions】</li>
	 *         <li>如果previous不为null，也就是mdb需要重新合并定义，就会调用
	 *         copyRelevantMergedBeanDefinitionCaches(previous, mbd)让mbd与previous进行合并。
	 *         </li>
	 *     </ol>
	 * </p>
	 * Return a RootBeanDefinition for the given bean, by merging with the
	 * parent if the given bean's definition is a child bean definition.
	 * <p>如果给定RootBeanDefinition是子BeanDefinition，则通过与父级合并返回
	 * RootBeanDefinition</p
	 *
	 * @param beanName     the name of the bean definition  -bean定义名称
	 * @param bd           the original bean definition (Root/ChildBeanDefinition)
	 *                     -- 原始BeanDefinition(Root/ChildBeanDefinition)
	 * @param containingBd the containing bean definition in case of inner bean,
	 *                     or {@code null} in case of a top-level bean
	 *                     -- 如果是内部bean，则为包含bean的定义；如果是顶级bean，则为null
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * -- 给定bean的（可能合并的）RootBeanDefinition
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 *                                      -- 如果Bean定义无效
	 */
	protected RootBeanDefinition getMergedBeanDefinition(
			String beanName, BeanDefinition bd, @Nullable BeanDefinition containingBd)
			throws BeanDefinitionStoreException {
		//同步： 使用 从bean名称映射到合并的RootBeanDefinition集合 进行加锁
		synchronized (this.mergedBeanDefinitions) {
			RootBeanDefinition mbd = null;
			RootBeanDefinition previous = null;

			//父级 bean 为空
			if (containingBd == null) {
				mbd = this.mergedBeanDefinitions.get(beanName);
			}
			//已经缓存的 mbd 不存在 / 或需要合并
			if (mbd == null || mbd.stale) {
				previous = mbd;
				//获取待合并的 BeanDefinition 的父级 BeanDefinition 的名字
				//父级 BeanDefinition 名字为 空
				if (bd.getParentName() == null) {
					// Use copy of given root bean definition.
					if (bd instanceof RootBeanDefinition) {
						// 如果当前待合同 bd已经是 RootBeanDefinition 则复制属性生成新的 mbd
						mbd = ((RootBeanDefinition) bd).cloneBeanDefinition();
					} else {
						//否则 new一个新的 mbd
						mbd = new RootBeanDefinition(bd);
					}
				}
				//父级 BeanDefinition 名字不为 空
				else {
					//子级 BeanDefinition 需要与父级BeanDefinition合并
					BeanDefinition pbd;
					try {
						//同样合并前对父级 BeanDefinition 名字进行处理
						String parentBeanName = transformedBeanName(bd.getParentName());
						//如果 父级 BeanDefinition 名字 和待合同bd名字不相同
						if (!beanName.equals(parentBeanName)) {
							//递归调用 getMergedBeanDefinition 因为生成的bd可能还是ChildBeanDefinition,所以一直递归
							pbd = getMergedBeanDefinition(parentBeanName);
						}
						//如果 父级 BeanDefinition 名字 和待合同bd名字相同
						else {
							//获取 当前 BeanFactory的 父级 BeanFactory
							BeanFactory parent = getParentBeanFactory();
							if (parent instanceof ConfigurableBeanFactory) {
								//递归调用
								pbd = ((ConfigurableBeanFactory) parent).getMergedBeanDefinition(parentBeanName);
							} else {
								throw new NoSuchBeanDefinitionException(parentBeanName,
										"Parent name '" + parentBeanName + "' is equal to bean name '" + beanName +
												"': cannot be resolved without a ConfigurableBeanFactory parent");
							}
						}
					} catch (NoSuchBeanDefinitionException ex) {
						throw new BeanDefinitionStoreException(bd.getResourceDescription(), beanName,
								"Could not resolve parent bean definition '" + bd.getParentName() + "'", ex);
					}
					//现在已经获取 bd 的parent bd到pbd
					// Deep copy with overridden values.
					mbd = new RootBeanDefinition(pbd);
					// 使用原始bd定义信息覆盖父级的定义信息:
					// 1. 如果在给定的bean定义中指定，则将覆盖beanClass
					// 2. 将始终从给定的bean定义中获取abstract,scope,lazyInit,autowireMode,
					// 			dependencyCheck和dependsOn
					// 3. 将给定bean定义中ConstructorArgumentValues,propertyValues,
					// 			methodOverrides 添加到现有的bean定义中
					// 4. 如果在给定的bean定义中指定，将覆盖factoryBeanName,factoryMethodName,
					// 		initMethodName,和destroyMethodName
					mbd.overrideFrom(bd);
				}

				// scope没有设置 则默认是 单例
				if (!StringUtils.hasLength(mbd.getScope())) {
					mbd.setScope(SCOPE_SINGLETON);
				}

				// A bean contained in a non-singleton bean cannot be a singleton itself.
				// Let's correct this on the fly here, since this might be the result of
				// parent-child merging for the outer bean, in which case the original inner bean
				// definition will not have inherited the merged outer bean's singleton status.
				// 非单例bean中包含的bean本身不能是单例。
				// 让我们在此即时进行更正，因为这可能是外部bean的父子合并的结果，在这种情况下，
				// 原始内部bean定义将不会继承合并的外部bean的单例状态。
				// 如果有传包含bean定义且包含bean定义不是单例但mbd又是单例
				if (containingBd != null && !containingBd.isSingleton() && mbd.isSingleton()) {
					//让mbd的作用域设置为跟containingBd的作用域一样
					mbd.setScope(containingBd.getScope());
				}

				// Cache the merged bean definition for the time being
				// (it might still get re-merged later on in order to pick up metadata changes)
				// 暂时缓存合并的bean定义
				// (稍后可能仍会重新合并以获取元数据更正)
				//如果没有传入包含bean定义 且 当前工厂是同意缓存bean元数据
				if (containingBd == null && isCacheBeanMetadata()) {
					//将beanName和mbd的关系添加到 从bean名称映射到合并的RootBeanDefinition集合中
					this.mergedBeanDefinitions.put(beanName, mbd);
				}
			}
			//如果存在 上一个 从bean名称映射到合并的RootBeanDefinition集合中 取出的mbd
			// 且该mbd需要重新合并定义
			if (previous != null) {
				//拿previous来对mdb进行重新合并定义：
				//1. 设置mbd的目标类型为previous的目标类型
				//2. 设置mbd的工厂bean标记为previous的工厂bean标记
				//3. 设置mbd的用于缓存给定bean定义的确定的Class为previous的用于缓存给定bean定义的确定的Class
				//4. 设置mbd的工厂方法返回类型为previous的工厂方法返回类型
				//5. 设置mbd的用于缓存用于自省的唯一工厂方法候选为previous的用于缓存用于自省的唯一工厂方法候选
				copyRelevantMergedBeanDefinitionCaches(previous, mbd);
			}
			return mbd;
		}
	}

	/**
	 * 复制相关的合并bean定义缓存，或者说拿previous来对mdb进行重新合并定义。
	 * <ul>
	 * 	<li>设置mbd的目标类型为previous的目标类型</li>
	 *  <li>设置mbd的工厂bean标记为previous的工厂bean标记</li>
	 *  <li>设置mbd的用于缓存给定bean定义的确定的Class为previous的用于缓存给定bean定义的确定的Class</li>
	 * 	<li>设置mbd的工厂方法返回类型为previous的工厂方法返回类型</li>
	 *  <li>设置mbd的用于缓存用于自省的唯一工厂方法候选为previous的用于缓存用于自省的唯一工厂方法候选</li>
	 * </ul>
	 *
	 * @param previous 原需要重新合并定义mbd
	 * @param mbd      当前mbd
	 */
	private void copyRelevantMergedBeanDefinitionCaches(RootBeanDefinition previous, RootBeanDefinition mbd) {
		//ObjectUtils.nullSafeEquals:确定给定的对象是否相等，如果两个都为null返回true ,
		// 		如果其中一个为null，返回false
		//mbd和previous的当前Bean类名称相同，工厂bean名称相同，工厂方法名相同
		if (ObjectUtils.nullSafeEquals(mbd.getBeanClassName(), previous.getBeanClassName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryBeanName(), previous.getFactoryBeanName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryMethodName(), previous.getFactoryMethodName())) {
			//获取mbd的目标类型
			ResolvableType targetType = mbd.targetType;
			//获取previous的目标类型
			ResolvableType previousTargetType = previous.targetType;
			//如果mdb的目标类型为null 或者 mdb的目标类型与previous的目标类型相同
			if (targetType == null || targetType.equals(previousTargetType)) {
				//设置mbd的目标类型为previous的目标类型
				mbd.targetType = previousTargetType;
				//设置mbd的工厂bean标记为previous的工厂bean标记
				mbd.isFactoryBean = previous.isFactoryBean;
				//设置mbd的用于缓存给定bean定义的确定的Class为previous的用于缓存给定bean定义的确定的Class
				mbd.resolvedTargetType = previous.resolvedTargetType;
				//设置mbd的工厂方法返回类型为previous的工厂方法返回类型
				mbd.factoryMethodReturnType = previous.factoryMethodReturnType;
				//设置mbd的用于缓存用于自省的唯一工厂方法候选为previous的用于缓存用于自省的唯一工厂方法候选
				mbd.factoryMethodToIntrospect = previous.factoryMethodToIntrospect;
			}
		}
	}

	/**
	 * Check the given merged bean definition,
	 * potentially throwing validation exceptions.
	 * <p>检查给定的合并后mbd，不合格会引发验证异常：
	 *  <ol>
	 *    <li>如果mbd所配置的bean是抽象的就抛出异常</li>
	 *  </ol>
	 * </p>
	 *
	 * @param mbd      the merged bean definition to check -- 要检查的合并后RootBeanDefinition
	 * @param beanName the name of the bean -- bean名
	 * @param args     the arguments for bean creation, if any -- 创建bean的参数（如果有）
	 * @throws BeanDefinitionStoreException in case of validation failure -- 如果验证失败
	 */
	protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, @Nullable Object[] args)
			throws BeanDefinitionStoreException {
		//如果mbd所配置的bean是抽象的
		if (mbd.isAbstract()) {
			//抛出Bean为抽象异常
			throw new BeanIsAbstractException(beanName);
		}
	}

	/**
	 * <p>将beanName对应的合并后RootBeanDefinition对象标记为重新合并定义</p>
	 * Remove the merged bean definition for the specified bean,
	 * recreating it on next access.
	 * <p>删除指定的bean的合并bean定义，在下次访问时重新创建</p>
	 * <p>获取beanName合并后RootBeanDefinition对象【变量 bd】，将bd标记为需要重新合并定义【RootBeanDefinition#stale】/p>
	 *
	 * @param beanName the bean name to clear the merged definition for
	 *                 - bean名称以清除其合并定义
	 */

	protected void clearMergedBeanDefinition(String beanName) {
		//从合并后BeanDefinition集合缓存中获取beanName对应的合并后RootBeanDefinition对象
		RootBeanDefinition bd = this.mergedBeanDefinitions.get(beanName);
		//如果成功获取到了bd
		if (bd != null) {
			//将bd标记为需要重新合并定义
			bd.stale = true;
		}
	}

	/**
	 * <p>默认实现 如果 mergedBeanDefinitions中的beanNamee没有资格缓存其BeanDefinition元数据时，将所对应的
	 * bd标记为需要重新合并定义</p>
	 * Clear the merged bean definition cache, removing entries for beans
	 * which are not considered eligible for full metadata caching yet.
	 * <p>清空合并的BeanDefinition缓存，删除尚未被认为符合完整元数据缓存条件的Bean条目</p>
	 * <p>Typically triggered after changes to the original bean definitions,
	 * e.g. after applying a {@code BeanFactoryPostProcessor}. Note that metadata
	 * for beans which have already been created at this point will be kept around.
	 * <p>通常在对原始BeanDefinition进行更改之后触发，例如 BeanFactoryPostProcessor 之后。注意，
	 * 此时已经创建的Bean的元数据将被保留</p>
	 *
	 * @since 4.2
	 */
	public void clearMetadataCache() {
		//mergedBeanDefinitions:从bean名称映射到合并的RootBeanDefinition
		//遍历 mergedBeanDefinitions
		this.mergedBeanDefinitions.forEach((beanName, bd) -> {
			//如果BeanName没有资格缓存其BeanDefinition元数据
			if (!isBeanEligibleForMetadataCaching(beanName)) {
				//将bd标记为需要重新合并定义
				bd.stale = true;
			}
		});
	}

	/**
	 * <p>为mdb解析出对应的bean class:
	 *  <ol>
	 *      <li>如果mbd指定了bean class,就直接返回该bean class</li>
	 *      <li>调用doResolveBeanClass(mbd, typesToMatch)来解析获取对应的bean Class对象然后返回出去。如果成功获取到
	 *      系统的安全管理器,使用特权的方式调用。</li>
	 *      <li>捕捉PrivilegedActionException,ClassNotFoundException异常和LinkageError错误，保证其异常或错误信息，
	 *      抛出CannotLoadBeanClassException</li>
	 *  </ol>
	 * </p>
	 * Resolve the bean class for the specified bean definition,
	 * resolving a bean class name into a Class reference (if necessary)
	 * and storing the resolved Class in the bean definition for further use.
	 * <p>为指定的bean定义解析bean类，将bean类名解析为Class引用（如果需要）,并
	 * 将解析后的Class存储在bean定义中以备将来使用。</p>
	 *
	 * @param mbd          the merged bean definition to determine the class for
	 *                     -- 合并的bean定义来确定其类
	 * @param beanName     the name of the bean (for error handling purposes)
	 *                     -- bean名称（用于错误处理）,用于发生异常时，描述异常信息
	 * @param typesToMatch -- 要匹配的类型，用于当该工厂有临时类加载器且该类加载器属于DecoratingClassLoader实例时，
	 *                     对这些要匹配的类型进行在临时类加载器中的排除，以交由父ClassLoader以常规方式处理
	 *                     默认情况下父classLoader是线程上下文类加载器】。<br/>
	 *                     the types to match in case of internal type matching purposes
	 *                     (also signals that the returned {@code Class} will never be exposed to application code)
	 *                     -- 内部类型匹配时要匹配的类型（也表示返回的Class永远不会暴露给应用程序代码）
	 * @return the resolved bean class (or {@code null} if none)
	 * -- 解析的Bean类(如果没有，则为null)
	 * @throws CannotLoadBeanClassException if we failed to load the class
	 *                                      -- 如果我们无法加载类
	 * @see #doResolveBeanClass(RootBeanDefinition, Class[])
	 */
	@Nullable
	protected Class<?> resolveBeanClass(RootBeanDefinition mbd, String beanName, Class<?>... typesToMatch)
			throws CannotLoadBeanClassException {
		try {
			//如果mbd指定了bean类
			if (mbd.hasBeanClass()) {
				//获取mbd的指定bean类
				return mbd.getBeanClass();
			}
			return doResolveBeanClass(mbd, typesToMatch);
		}//捕捉 未找到类异常
		catch (ClassNotFoundException ex) {
			//包装异常信息，抛出CannotLoadBeanClassException
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		}
		//不兼容的变化
		catch (LinkageError err) {
			//包装错误信息，抛出CannotLoadBeanClassException
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), err);
		}
	}

	/**
	 * <p>获取mbd配置的bean类名，将bean类名解析为Class对象,并将解析后的Class对象缓存在mdb中以备将来使用：
	 *  <ol>
	 *    <li>获取该工厂的加载bean用的类加载器【变量 beanClassLoader】</li>
	 *    <li>声明一个动态类加载器【变量 dynamicLoader】，默认引用beanClassLoader</li>
	 *    <li>声明一个表示mdb的配置的bean类名需要重新被dynamicLoader加载的标记，默认不需要。【变量 freshResolve】</li>
	 *    <li>如果传入了typesToMatch，且该工厂有临时类加载器【变量 tempClassLoader】：
	 *    	<ol>
	 *    	   <li>改变dynamicLoader引用为tempClassLoader</li>
	 *    	   <li>标记mdb的配置的bean类名需要重新被dynamicLoader加载</li>
	 *    	   <li>如果tempClassLoader属于DecoratingClassLoader实例,会对tempClassLoader进行强转为DecoratingClassLoader
	 *    	   【变量 dcl】，然后对typeToMatch在dcl中的排除，使其交由其父classLoader【默认情况下父classLoader是线程上下文类加载器】
	 *    	   进行常规方式处理。</li>
	 *    	</ol>
	 *    </li>
	 *    <li>从mbd中获取配置的bean类名【变量名 className】</li>
	 *    <li>如果获取到className:
	 *    	<ol>
	 *    	  <li>评估beanDefinition中包含的className,如果className是可解析表达式，会对其进行解析，否则直接返回className.【变量 evaluated】</li>
	 *    	  <li>如果className与evaluated不一样:
	 *    	   <ol>
	 *    	     <li>如果evaluated属于Class实例,强转evaluated为Class对象并返回出去</li>
	 *    	     <li>如果evaluated属于String实例,将evaluated作为className的值,然后标记mdb配置的bean类名需要重新
	 *    	     被dynamicLoader加载</li>
	 *    	     <li>否则：抛出非法状态异常：无效的类名表达式结果：evaluated</li>
	 *    	   </ol>
	 *    	  </li>
	 *    	  <li>如果mdb的配置的bean类名需要重新被dynamicLoader加载:
	 *    	   <ol>
	 *    	     <li>如果dynamicLoader不为null,使用dynamicLoader加载className对应的类型，并返回加载成功的Class对象.
	 *    	     	同时捕捉未找到类异常【变量ex】
	 *    	     </li>
	 *    	     <li>如果抛出了ex.打印追踪日志：无法从dynamicLoader中加载类[className]:ex</li>
	 *    	     <li>调用ClassUtils.forName(className, dynamicLoader)来获取Class对象并返回出去</li>
	 *    	   </ol>
	 *    	  </li>
	 *    	</ol>
	 *    </li>
	 *    <li>否则，使用beanClassLoader加载mbd所配置的Bean类名的Class对象并返回出去</li>
	 *  </ol>
	 * </p>
	 *
	 * @param mbd          -- 合并的bean定义来确定其类
	 * @param typesToMatch -- 要匹配的类型，用于当该工厂有临时类加载器且该类加载器属于DecoratingClassLoader实例时，
	 *                     对这些要匹配的类型进行在临时类加载器中的排除，以交由父ClassLoader以常规方式处理
	 *                     【默认情况下父classLoader是线程上下文类加载器】。
	 * @return -- 解析的Bean类(如果没有，则为null)
	 * @throws ClassNotFoundException -- 如果我们无法加载类
	 */
	@Nullable
	private Class<?> doResolveBeanClass(RootBeanDefinition mbd, Class<?>... typesToMatch)
			throws ClassNotFoundException {
		//获取该工厂的加载bean用的类加载器
		ClassLoader beanClassLoader = getBeanClassLoader();
		//初始化动态类加载器为该工厂的加载bean用的类加载器,如果该工厂有
		// 临时类加载器器时，该动态类加载器就是该工厂的临时类加载器
		ClassLoader dynamicLoader = beanClassLoader;
		//表示mdb的配置的bean类名需要重新被dynamicLoader加载的标记，默认不需要
		boolean freshResolve = false;
		//如果有传入要匹配的类型
		if (!ObjectUtils.isEmpty(typesToMatch)) {
			// When just doing type checks (i.e. not creating an actual instance yet),
			// use the specified temporary class loader (e.g. in a weaving scenario).
			// 仅进行类型检查时（即尚未创建实际实例），请使用指定的临时类加载器
			//获取该工厂的临时类加载器，该临时类加载器专门用于类型匹配
			ClassLoader tempClassLoader = getTempClassLoader();
			//如果成功获取到临时类加载器
			if (tempClassLoader != null) {
				//以该工厂的临时类加载器作为动态类加载器
				dynamicLoader = tempClassLoader;
				//标记mdb的配置的bean类名需要重新被dynamicLoader加载
				freshResolve = true;
				//DecoratingClassLoader:装饰ClassLoader的基类,提供对排除的包和类的通用处理
				//如果临时类加载器是DecoratingClassLoader的基类
				if (tempClassLoader instanceof DecoratingClassLoader dcl) {
					//对要匹配的类型进行在装饰类加载器中的排除，以交由父ClassLoader以常规方式处理
					for (Class<?> typeToMatch : typesToMatch) {
						dcl.excludeClass(typeToMatch.getName());
					}
				}
			}
		}
		//从mbd中获取配置的bean类名
		String className = mbd.getBeanClassName();
		//如果能成功获得配置的bean类名
		if (className != null) {
			//评估beanDefinition中包含的className,如果className是可解析表达式，会对其进行解析，否则直接返回className:
			Object evaluated = evaluateBeanDefinitionString(className, mbd);
			//如果className与解析后的值不一样
			if (!className.equals(evaluated)) {
				// A dynamically resolved expression, supported as of 4.2...
				// 从4.2开始支持动态解析的表达式
				// 如果evaluated属于Class实例
				if (evaluated instanceof Class<?> clazz) {
					//强转evaluated为Class对象并返回出去
					return clazz;
				}
				// 如果evaluated属于String实例
				else if (evaluated instanceof String name) {
					//将evaluated作为className的值
					className = name;
					//标记mdb的配置的bean类名需要重新被dynamicLoader加载
					freshResolve = true;
				} else {
					//抛出非法状态异常：无效的类名表达式结果：evaluated
					throw new IllegalStateException("Invalid class name expression result: " + evaluated);
				}
			}
			//如果mdb的配置的bean类名需要重新被dynamicLoader加载
			if (freshResolve) {
				// When resolving against a temporary class loader, exit early in order
				// to avoid storing the resolved Class in the bean definition.
				// 当使用临时类加载器进行解析时，请尽早退出以避免将已解析的类存储在BeanDefinition中
				// 如果动态类加载器不为null
				if (dynamicLoader != null) {
					try {
						//使用dynamicLoader加载className对应的类型，并返回加载成功的Class对象
						return dynamicLoader.loadClass(className);
					}
					//捕捉 未找到类异常，
					catch (ClassNotFoundException ex) {
						if (logger.isTraceEnabled()) {
							//打印追踪日志：无法从dynamicLoader中加载类[className]:ex
							logger.trace("Could not load class [" + className + "] from " + dynamicLoader + ": " + ex);
						}
					}
				}
				//使用classLoader加载name对应的Class对象,该方式是Spring用于代替Class.forName()的方法，支持返回原始的类实例(如'int')
				// 和数组类名 (如'String[]')。此外，它还能够以Java source样式解析内部类名(如:'java.lang.Thread.State'
				// 而不是'java.lang.Thread$State')
				return ClassUtils.forName(className, dynamicLoader);
			}
		}

		// Resolve regularly, caching the result in the BeanDefinition...
		// 定期解析，将结果缓存在BeanDefinition中...
		// 使用classLoader加载当前BeanDefinition对象所配置的Bean类名的Class对象（每次调用都会重新加载,可通过
		// AbstractBeanDefinition#getBeanClass 获取缓存）：
		return mbd.resolveBeanClass(beanClassLoader);
	}

	/**
	 * <p>评估benaDefinition中包含的value,如果value是可解析表达式，会对其进行解析，否则直接返回value:
	 *  <ol>
	 *    <li>如果该工厂没有设置bean定义值中表达式的解析策略【beanExpressionResolver】，就职返回value【默认情况下，
	 *    工厂是配置StandardBeanExpressionResolver作为beanExpressionResolver】</li>
	 *    <li>如果beanDefinition不为null,获取beanDefinition的当前目标作用域名,然后将其作用域名装换为Scope对象，赋值给
	 *    【scope】</li>
	 *    <li>使用beanExpressionResolver解析value，并返回其解析结果。在解析过程中，会判断value是否为表达式，如果不是
	 *    就会直接返回value作为其解析结果</li>
	 *  </ol>
	 * </p>
	 * Evaluate the given String as contained in a bean definition,
	 * potentially resolving it as an expression.
	 * <p>评估bean定义中包含的给定String，可能将其解析为表达式</p>
	 * @param value the value to check -- 要检查的值
	 * @param beanDefinition the bean definition that the value comes from -- 值所来自的bean定义
	 * @return the resolved value -- 解析后的值
	 * @see #setBeanExpressionResolver
	 */
	@Nullable
	protected Object evaluateBeanDefinitionString(@Nullable String value, @Nullable BeanDefinition beanDefinition) {
		//如果该工厂没有设置bean定义值中表达式的解析策略
		if (this.beanExpressionResolver == null) {
			//直接返回要检查的值
			return value;
		}
		//值所来自的bean定义的当前目标作用域
		Scope scope = null;
		//如果有传入值所来自的bean定义
		if (beanDefinition != null) {
			//获取值所来自的bean定义的当前目标作用域名
			String scopeName = beanDefinition.getScope();
			//如果成功获得值所来自的bean定义的当前目标作用域名
			if (scopeName != null) {
				//获取scopeName对应的Scope对象
				scope = getRegisteredScope(scopeName);
			}
		}
		//评估value作为表达式（如果适用）；否则按原样返回值
		return this.beanExpressionResolver.evaluate(value, new BeanExpressionContext(this, scope));
	}


	/**
	 * <p>
	 *     预测mdb所指的bean的最终bean类型(已处理bean实例的类型)。大概逻辑：
	 *     <ol>
	 *         <li>如果成功获取到mbd的目标类型【RootBeanDefinition#getTargetType】，就将其返回出去</li>
	 *     	   <li>如果mbd有设置mbd的工厂方法名，则直接返回 null </li>
	 *     	   <li>返回resolveBeanClass(RootBeanDefinition,String,Class<?>...)的执行结果，
	 *     	   	主要是为mbd解析bean类，将beanName解析为Class引用（如果需要）,并将解析后的Class存储
	 *     	   	在mbd中以备将来使用。</li>
	 *     </ol>
	 * </p>
	 * Predict the eventual bean type (of the processed bean instance) for the
	 * specified bean. Called by {@link #getType} and {@link #isTypeMatch}.
	 * Does not need to handle FactoryBeans specifically, since it is only
	 * supposed to operate on the raw bean type.
	 * <p>预测指定bean的最终bean类型(已处理bean实例的类型)。
	 * 通过调用getType和isTypeMatch。不需要专门处理FactoryBeans，因为它仅应在
	 * 原始bean类型上运行</p>
	 * <p>This implementation is simplistic in that it is not able to
	 * handle factory methods and InstantiationAwareBeanPostProcessors.
	 * It only predicts the bean type correctly for a standard bean.
	 * To be overridden in subclasses, applying more sophisticated type detection.
	 * <p>此实现非常简单，因为它无法处理工厂方法和InstantiationAwareBeanProcess.
	 * 它只能为标准Bean正确预测Bean类型。要在子类中覆盖，请应用更复杂的类型检查</p>
	 * @param beanName the name of the bean -- bean名,用于发生异常时，描述异常信息
	 * @param mbd the merged bean definition to determine the type for
	 *            -- 合并的bean定义以确定其类型
	 * @param typesToMatch
	 * 要匹配的类型，用于当该工厂有临时类加载器且该类加载器属于DecoratingClassLoader实例时，
	 * 对这些要匹配的类型进行在临时类加载器中的排除，以交由父ClassLoader以常规方式处理
	 * 默认情况下父classLoader是线程上下文类加载器】
	 * <br/>
	 * the types to match in case of internal type matching purposes
	 * (also signals that the returned {@code Class} will never be exposed to application code)
	 *  -- 内部类型培评时要匹配的类型（也表示返回的Class永远不会保留应用程序代码）
	 * @return the type of the bean, or {@code null} if not predictable
	 * -- Bean的类型；如果不可预测，则为null
	 */
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		//获取mbd的目标类型
		Class<?> targetType = mbd.getTargetType();
		//如果成功获得mbd的目标类型
		if (targetType != null) {
			//返回 mbd的目标类型
			return targetType;
		}
		//如果有设置mbd的工厂方法名
		if (mbd.getFactoryMethodName() != null) {
			//返回null，表示不可预测
			return null;
		}
		//为mbd解析bean类，将beanName解析为Class引用（如果需要）,并将解析后的Class存储在mbd中以备将来使用。
		return resolveBeanClass(mbd, beanName, typesToMatch);
	}

	/**
	 * <p>判断beanName,mbd所指的bean是否FactoryBean:
	 *  <ol>
	 *   <li>定义一个存储mbd是否是FactoryBean的标记【变量 result】</li>
	 *   <li>如果result为null:
	 *    <ol>
	 *     <li>根据预测指定bean的最终bean类型【变量 beanType】</li>
	 *     <li>如果成功获取最终bean类型，且 最终bean类型属于FactoryBean类型则认为是FactoryBean，然后设置result为true;
	 *     否则为false</li>
	 *     <li>将result缓存在mbd中</li>
	 *    </ol>
	 *   </li>
	 *   <li>将result返回出去</li>
	 *  </ol>
	 * </p>
	 * Check whether the given bean is defined as a {@link FactoryBean}.
	 * <p>检查给定的bean是否已定义为FactoryBean。</p>
	 * <p>主要通过传入beanName和mbd到predictBeanType方法中预测其最终bean类型，然后判断该类型
	 * 是否是FactoryBean类型，并将判断结果缓存到mbd中</p>
	 * @param beanName the name of the bean -- bean名
	 * @param mbd the corresponding bean definition -- 相应的bean定义
	 */
	protected boolean isFactoryBean(String beanName, RootBeanDefinition mbd) {
		//获取isFactoryBean属性
		Boolean result = mbd.isFactoryBean;
		//如果该属性还没有赋值
		if (result == null) {
			//那么预测指定 bean 的最终 bean 类型是否是FactoryBean类型
			Class<?> beanType = predictBeanType(beanName, mbd, FactoryBean.class);
			result = (beanType != null && FactoryBean.class.isAssignableFrom(beanType));
			//为该属性赋值
			mbd.isFactoryBean = result;
		}
		//返回结果
		return result;
	}

	/**
	 * <p>
	 * 获取beanName,mbd所指的FactoryBean要创建的bean类型。<br/>逻辑步骤：
	 *     	<ol>
	 *     	    <li>通过mbd中的属性名FactoryBean.OBJECT_TYPE_ATTRIBUTE的值来确定类型</li>
	 *     	    <li>在允许初始化FactoryBean且mbd配置了单例的情况下，尝试使用该beanName的BeanFactory
	 *     	    对象来获取factoryBean的创建出来的对象的类型</li>
	 *     	    <li>还是拿不到就返回ResolvableType.NONE</li>
	 *     	</ol>
	 * </p>
	 * <p>
	 * Determine the bean type for the given FaisTypeMatchctoryBean definition, as far as possible.
	 * Only called if there is no singleton instance registered for the target bean
	 * already. Implementations are only allowed to instantiate the factory bean if
	 * {@code allowInit} is {@code true}, otherwise they should try to determine the
	 * result through other means.
	 * <p>尽可能确定给定FactoryBean定义的bean类型。仅在尚未为目标bean注册单例实例调用。
	 * 如果allowInit为true，则仅允许实现实例化工厂bean，否则实现应尝试通过其他方式
	 * 确定结果。</p>
	 * <p>If no {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} if set on the bean definition
	 * and {@code allowInit} is {@code true}, the default implementation will create
	 * the FactoryBean via {@code getBean} to call its {@code getObjectType} method.
	 * Subclasses are encouraged to optimize this, typically by inspecting the generic
	 * signature of the factory bean class or the factory method that creates it. If
	 * subclasses do instantiate the FactoryBean, they should consider trying the
	 * {@code getObjectType} method without fully populating the bean. If this fails, a
	 * full FactoryBean creation as performed by this implementation should be used as
	 * fallback.
	 * <p>如果在bean定义上没有设置Factory.OBJECT_TYPE_ATTRIBUTE且allowInit为true,则默认
	 * 实现将通过getBean创建FactoryBean来调用其getObjectType方法。鼓励子类对此进行优化，
	 * 通常是通过检查工厂bean类的通用签名或创建它的工厂方法来进行优化。如果子类确实实例化
	 * 了FactoryBean，则应考虑在不完全填充bean的情况下尝试，使用getObjectType方法。如果失败，
	 * 则应使用此实例执行的完整FactouryBean创建作为后备。</p>
	 *
	 * @param beanName  the name of the bean -- bean名
	 * @param mbd       the merged bean definition for the bean -- bean的合并bean定义
	 * @param allowInit if initialization of the FactoryBean is permitted -- 如果允许初始化FactoryBean
	 * @return the type for the bean if determinable, otherwise {@code ResolvableType.NONE}
	 * -- Bean的类型（如果可以确定），否则为{@code ResolvableType.NONE}
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 * @since 5.2
	 */
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		//通过检查mbd中的属性名FactoryBean.OBJECT_TYPE_ATTRIBUTE的值来
		// 	确定FactoryBean的bean类型
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		//如果得到的bean类型不是ResolvableType.NONE
		//ResolvableType.NONE:表示没有可用的值，相当于 null
		if (result != ResolvableType.NONE) {
			//返回该Bean类型
			return result;
		}
		//如果允许初始化FactoryBean 且 mbd配置了单例
		if (allowInit && mbd.isSingleton()) {
			try {
				//获取该beanName的BeanFactory对象
				FactoryBean<?> factoryBean = doGetBean(FACTORY_BEAN_PREFIX + beanName, FactoryBean.class, null, true);
				//获取factoryBean的创建出来的对象的类型
				Class<?> objectType = getTypeForFactoryBean(factoryBean);
				//如果成功得到对象类型就将其封装成ResolvableType对象，否则返回ResolvableType.NONE
				// ResolvableType.NONE表示没有可用的值，相当于 null
				return (objectType != null ? ResolvableType.forClass(objectType) : ResolvableType.NONE);
			}
			//捕捉 尝试从Bean定义创建Bean时BeanFactory遇到错误时引发的异常
			catch (BeanCreationException ex) {
				//BeanCurrentlyInCreationException:在引用当前正在创建的bean时引发异常。通常在构造函数自动装配与
				// 	当前构造的bean匹配时发生。
				//如果ex包含BeanCurrentlyInCreationException异常
				if (ex.contains(BeanCurrentlyInCreationException.class)) {
					//跟踪级别日志信息：当前在FactoryBean类型检查中创建的Bean:异常信息
					logger.trace(LogMessage.format("Bean currently in creation on FactoryBean type check: %s", ex));
				}
				//如果mbd配置了延迟初始化
				else if (mbd.isLazyInit()) {
					//跟踪级别日志信息：延迟FactoryBean类型检查中的Bean创建异常:异常信息
					logger.trace(LogMessage.format("Bean creation exception on lazy FactoryBean type check: %s", ex));
				} else {
					//跟踪级别日志信息：非延迟FactoryBean类型检查中的Bean创建异常：%s
					logger.debug(LogMessage.format("Bean creation exception on eager FactoryBean type check: %s", ex));
				}
				//将ex添加到 抑制异常列表 中，注意抑制异常列表是Set集合
				onSuppressedException(ex);
			}
		}
		// 如果 无法通过mbd中的属性名FactoryBean.OBJECT_TYPE_ATTRIBUTE的值来确定类型
		// 又 不允许初始化FactoryBean 或者 mbd不是配置成单例，
		// 或者 允许初始化FactoryBean且mbd配置了单例时，尝试该beanName的BeanFactory对象来
		// 获取factoryBean的创建出来的对象的类型但抛出了尝试从Bean定义创建Bean时BeanFactory
		// 遇到错误时引发的异常 ，都会返回ResolvableType.NONE
		return ResolvableType.NONE;
	}

	/**
	 * Determine the bean type for a FactoryBean by inspecting its attributes for a
	 * {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} value.
	 * <p>通过检查FactoryBean.OBJECT_TYPE_ATTRIBUTE值的属性来确定FactoryBean的bean类型</p>
	 *
	 * @param attributes the attributes to inspect -- 要检查的属性
	 * @return a {@link ResolvableType} extracted from the attributes or
	 * {@code ResolvableType.NONE}
	 * -- 从属性或ResolvableType.NONE中提取ResolvableType
	 * @since 5.2
	 */
	ResolvableType getTypeForFactoryBeanFromAttributes(AttributeAccessor attributes) {
		//获取FactoryBean.OBJECT_TYPE_ATTRIUTE在BeanDefinition对象的属性值
		Object attribute = attributes.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
		//如果属性值是ResolvableType的实例
		if (attribute instanceof ResolvableType resolvableType) {
			//强转并返回该属性值
			return resolvableType;
		}
		//如果属性值是Class实例
		if (attribute instanceof Class<?> clazz) {
			//使用ResolvableType封装属性值后返回
			return ResolvableType.forClass(clazz);
		}
		//如果没有成功获取到FactoryBean.OBJECT_TYPE_ATTRIUTE在BeanDefinition对象的属性值
		// 则返回 ResolvableType.NONE，
		// ResolvableType.NONE：表示没有可用的值，相当于 null
		return ResolvableType.NONE;
	}

	/**
	 * Mark the specified bean as already created (or about to be created).
	 * 将指定的 bean 标记为已创建（或即将创建）。
	 * <p>This allows the bean factory to optimize its caching for repeated
	 * creation of the specified bean.
	 * 这允许 bean 工厂优化其缓存，以重复创建指定的 bean。
	 *
	 * @param beanName the name of the bean
	 */
	protected void markBeanAsCreated(String beanName) {
		//如果alreadyCreated缓存没有包含当前beanName
		if (!this.alreadyCreated.contains(beanName)) {
			synchronized (this.mergedBeanDefinitions) {
				// 再次检查一次：DCL 双重校验
				if (!isBeanEligibleForMetadataCaching(beanName)) {
					// Let the bean definition get re-merged now that we're actually creating
					// the bean... just in case some of its metadata changed in the meantime.
					//在创建bean期间有可能有一些元数据的变化，因此清除mergedBeanDefinitions缓存中该beanName的合并缓存
					//清除的方式是将stale属性设置为true，让BeanDefinition可以重新合并
					clearMergedBeanDefinition(beanName);
				}
				//beanName添加到alreadyCreated缓存中
				this.alreadyCreated.add(beanName);
			}
		}
	}

	/**
	 * Perform appropriate cleanup of cached metadata after bean creation failed.
	 * <p>在Bean创建失败后，对缓存的元数据执行适当的清理</p>
	 *
	 * @param beanName the name of the bean -- bean名
	 */
	protected void cleanupAfterBeanCreationFailure(String beanName) {
		//mergedBeanDefinitions:从bean名称映射到合并的RootBeanDefinition
		//使用mergedBeanDefinitions加锁，保证线程安全
		synchronized (this.mergedBeanDefinitions) {
			//alreadyCreated:至少已经创建一次的bean名称
			//将beanName从alreadyCreated中删除
			this.alreadyCreated.remove(beanName);
		}
	}

	/**
	 * Determine whether the specified bean is eligible for having
	 * its bean definition metadata cached.
	 * <p>确定指定的Bean是否有资格缓存其BeanDefinition元数据</p>
	 *
	 * @param beanName the name of the bean -- bean名
	 * @return {@code true} if the bean's metadata may be cached
	 * at this point already
	 * -- 如果此时已经缓存了Bean的元数据，则为true
	 */
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		// alreadyCreated:至少已经创建一次的bean名称
		//返回 alreadyCreated 是否包含 beanName 的结果
		return this.alreadyCreated.contains(beanName);
	}

	/**
	 * Remove the singleton instance (if any) for the given bean name,
	 * but only if it hasn't been used for other purposes than type checking.
	 * <p>删除给定Bean名称的单例实例(如果有的话)，但仅当它没有用于类型检查之外的其他目的时才删除</p>
	 *
	 * @param beanName the name of the bean
	 *                 -- bean名
	 * @return {@code true} if actually removed, {@code false} otherwise
	 * -- 如实际删除就为true;否则为false
	 */
	protected boolean removeSingletonIfCreatedForTypeCheckOnly(String beanName) {
		//如果已创建的bean名称中没有该beanName对应对象
		if (!this.alreadyCreated.contains(beanName)) {
			// 1.从该工厂单例缓存中删除具有给定名称的Bean。如果创建失败，则能够清理饿汉式注册 的单例
			// 2.FactoryBeanRegistrySupport重写以清除FactoryBean对象缓存
			// 3.AbstractAutowireCapableBeanFactory重写 以清除FactoryBean对象缓存
			removeSingleton(beanName);
			//有删除时返回true
			return true;
		} else {
			//没有删除时返回false
			return false;
		}
	}

	/**
	 * <p>该工厂是否已经开始创建Bean：只要alreadyCreated【至少已经创建一次的bean名称集合】不为空，
	 * 返回true，表示该工厂已经开始创建Bean；否则返回false</p>
	 * Check whether this factory's bean creation phase already started,
	 * i.e. whether any bean has been marked as created in the meantime.
	 * <p>检查该工厂的Bean创建阶段是否已经开始，即在此期间是否有任何Bean被标记为已
	 * 创建</p>
	 *
	 * @see #markBeanAsCreated
	 * @since 4.2.2
	 */
	protected boolean hasBeanCreationStarted() {
		//只要alreadyCreated【至少已经创建一次的bean名称集合】不为空，返回true，表示该工厂已经开始创建Bean；
		// 否则返回false
		return !this.alreadyCreated.isEmpty();
	}

	/**
	 * <p>从 beanInstance 中获取公开的Bean对象，主要处理beanInstance是FactoryBean对象的情况，如果不是FactoryBean会直接
	 * 返回beanInstance实例</p>
	 * Get the object for the given bean instance, either the bean
	 * instance itself or its created object in case of a FactoryBean.
	 * <p>获取给定Bean实例对象，对于FactoryBean，可以是Bean实例本身，也可以
	 * 是它创建的对象</p>
	 *
	 * @param beanInstance the shared bean instance - 共享bean实例
	 * @param name         name that may include factory dereference prefix - 可能包含工厂取消引用前缀的名字
	 * @param beanName     the canonical bean name - 规范bean名
	 * @param mbd          the merged bean definition - 合并的bean定义
	 * @return the object to expose for the bean - 为bean公开的对象
	 */
	protected Object getObjectForBeanInstance(
			Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

		// Don't let calling code try to dereference the factory if the bean isn't a factory.
		//容器已经得到了Bean实例对象，这个实例对象可能是一个普通的Bean，
		//也可能是一个工厂Bean，如果是一个工厂Bean，则使用它创建一个Bean实例对象，
		//如果调用本身就想获得一个容器的引用，则指定返回这个工厂Bean实例对象
		//若为工厂类引用（name 以 & 开头） 且 Bean实例也不是 FactoryBean
		if (BeanFactoryUtils.isFactoryDereference(name)) {
			//如果beanInstance属于NullBean，这是对getObject方法返回的null的包装
			if (beanInstance instanceof NullBean) {
				//那么返回beanInstance
				return beanInstance;
			}
			if (!(beanInstance instanceof FactoryBean)) {
				throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
			}
			if (mbd != null) {
				mbd.isFactoryBean = true;
			}
			//如果类型是FactoryBean，直接返回beanInstance
			return beanInstance;
		}

		// Now we have the bean instance, which may be a normal bean or a FactoryBean.
		// If it's a FactoryBean, we use it to create a bean instance, unless the
		// caller actually wants a reference to the factory.
		if (!(beanInstance instanceof FactoryBean<?> factoryBean)) {
			return beanInstance;
		}

		Object object = null;
		//若 BeanDefinition 为 null，则从缓存中加载 Bean 对象
		if (mbd != null) {
			mbd.isFactoryBean = true;
		} else {
			//从Bean工厂缓存中获取给定名称的Bean实例对象
			object = getCachedObjectForFactoryBean(beanName);
		}
		// 若 object 依然为空，则可以确认，beanInstance 一定是 FactoryBean 。从而，使用 FactoryBean 获得 Bean 对象
		if (object == null) {
			// Return bean instance from factory.
			// Caches object obtained from FactoryBean if it is a singleton.
			// 检测是否定义 beanName
			if (mbd == null && containsBeanDefinition(beanName)) {
				//从容器中获取指定名称的Bean定义，如果继承基类，则合并基类相关属性
				mbd = getMergedLocalBeanDefinition(beanName);
			}
			//如果从容器得到Bean定义信息，并且Bean定义信息不是虚构的，则让工厂Bean生产Bean实例对象
			boolean synthetic = (mbd != null && mbd.isSynthetic());
			//调用FactoryBeanRegistrySupport类的getObjectFromFactoryBean方法，实现工厂Bean生产Bean对象实例的过程
			object = getObjectFromFactoryBean(factoryBean, beanName, !synthetic);
		}
		return object;
	}

	/**
	 * <p>判断beanName是否已在该工厂中使用,即beanName是否是别名 或该工厂是否已包含beanName的bean对象
	 * 或 该工厂是否已经为beanName注册了依赖Bean关系</p>
	 * Determine whether the given bean name is already in use within this factory,
	 * i.e. whether there is a local bean or alias registered under this name or
	 * an inner bean created with this name.
	 * <p>确定给定的Bean名是否已在该工厂中使用,即是否存在以该名称注册的本地Bean或别名,或是否
	 * 以此名称创建的内部Bean</p>
	 *
	 * @param beanName the name to check 要检查的名称
	 */
	public boolean isBeanNameInUse(String beanName) {
		// 判断是否bean名是否是别名 或者 本地BeanFactory包含beanName的bean对象 或者 已经为beanName注册了依赖Bean关系
		return isAlias(beanName) || containsLocalBean(beanName) || hasDependentBean(beanName);
	}

	/**
	 * Determine whether the given bean requires destruction on shutdown.
	 * <p>确定给定Bean在关闭时是否需要销毁</p>
	 * <p>The default implementation checks the DisposableBean interface as well as
	 * a specified destroy method and registered DestructionAwareBeanPostProcessors.
	 * <p>默认实现会检查一次性Bean接口以及指定的销毁方法和注册的 DestructionAwareBeanPostProcessors </p>
	 *
	 * @param bean the bean instance to check
	 *             -- 要检查的Bean实例
	 * @param mbd  the corresponding bean definition
	 *             -- 对应的BeanDefinition
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see AbstractBeanDefinition#getDestroyMethodName()
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean requiresDestruction(Object bean, RootBeanDefinition mbd) {
		//DestructionAwareBeanPostProcessor ：该处理器将在关闭时应用于单例Bean
		// 如果 bean类不是 NullBean && (如果bean有destroy方法 || (该工厂持有一个 DestructionAwareBeanPostProcessor) &&
		// 	Bean有应用于它的可识别销毁的后处理器)) 就为true;否则返回false
		return (bean.getClass() != NullBean.class && (DisposableBeanAdapter.hasDestroyMethod(bean, mbd) ||
				(hasDestructionAwareBeanPostProcessors() && DisposableBeanAdapter.hasApplicableProcessors(
						bean, getBeanPostProcessorCache().destructionAware))));
	}

	/**
	 * Add the given bean to the list of disposable beans in this factory,
	 * registering its DisposableBean interface and/or the given destroy method
	 * to be called on factory shutdown (if applicable). Only applies to singletons.
	 * <p>将给定Bean添加到该工厂中的可丢弃Bean列表中，注册器可丢弃Bean接口 和/或 在工厂关闭
	 * 时调用给定销毁方法（如果适用）。只适用单例</p>
	 *
	 * @param beanName the name of the bean -- bean名
	 * @param bean     the bean instance -- bean实例
	 * @param mbd      the bean definition for the bean -- bean的BeanDefinition
	 * @see RootBeanDefinition#isSingleton
	 * @see RootBeanDefinition#getDependsOn
	 * @see #registerDisposableBean
	 * @see #registerDependentBean
	 */
	protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
		// 1.mbd的scope不是prototype && 给定的bean需要在关闭时销毁
		if (!mbd.isPrototype() && requiresDestruction(bean, mbd)) {
			if (mbd.isSingleton()) {
				// Register a DisposableBean implementation that performs all destruction
				// work for the given bean: DestructionAwareBeanPostProcessors,
				// DisposableBean interface, custom destroy method.
				// 2.单例模式下注册用于销毁的bean到disposableBeans缓存，执行给定bean的所有销毁工作：
				// DestructionAwareBeanPostProcessors，DisposableBean接口，自定义销毁方法
				// 2.1 DisposableBeanAdapter：使用DisposableBeanAdapter来封装用于销毁的bean
				registerDisposableBean(beanName, new DisposableBeanAdapter(
						bean, beanName, mbd, getBeanPostProcessorCache().destructionAware));
			} else {
				// A bean with a custom scope...
				// 3.自定义scope处理
				Scope scope = this.scopes.get(mbd.getScope());
				if (scope == null) {
					//非法状态异常：无作用登记为作用名称'mbd.getScope'
					throw new IllegalStateException("No Scope registered for scope name '" + mbd.getScope() + "'");
				}
				//注册一个回调，在销毁作用域中将构建Bean对应的DisposableBeanAdapter对象指定(或者在销毁整个作用域时执行，
				// 如果作用域没有销毁单个对象，而是全部终止)
				scope.registerDestructionCallback(beanName, new DisposableBeanAdapter(
						bean, beanName, mbd, getBeanPostProcessorCache().destructionAware));
			}
		}
	}


	//---------------------------------------------------------------------
	// Abstract methods to be implemented by subclasses
	//---------------------------------------------------------------------

	/**
	 * Check if this bean factory contains a bean definition with the given name.
	 * Does not consider any hierarchy this factory may participate in.
	 * Invoked by {@code containsBean} when no cached singleton instance is found.
	 * <p>该BeanFactory是否包含beanName的BeanDefinition对象。不考虑工厂可能参与的任何层次结构。
	 * 未找到缓存的单例实例时，由{@code containsBena}调用。</p>
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * <p>根据具体bean工厂实现的性质，此操作可能很昂贵（例如，由于在外部注册表中进行目录
	 * 寻找）。但是，对应可列出的bean工厂，这通常只相当于本地哈希查找：因此，该操作是该处
	 * 工厂接口的一部分。在这种情况下，该模板方法和公共接口方法都可以使用相同的实现。</p>
	 *
	 * @param beanName the name of the bean to look for - 要查找的bean名
	 * @return if this bean factory contains a bean definition with the given name
	 * - 如果此bean工厂包含具有给定名称的bean定义。
	 * @see #containsBean
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 */
	protected abstract boolean containsBeanDefinition(String beanName);

	/**
	 * Return the bean definition for the given bean name.
	 * <p>返回给定bean名称的bean定义</p>
	 * Subclasses should normally implement caching, as this method is invoked
	 * by this class every time bean definition metadata is needed.
	 * <p>子类通常应实现缓存，因为每次需要bean定义元数据时，此类便会调用此方法</p>
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 * <p>根据具体bean工厂实现的性质，此操作可能很昂贵（例如，由于外部注册表中进行目录
	 * 查找）。但是，对于可列出的bean工厂，这通常只相当于本地哈希查找：因此，该操作是
	 * 处于公共接口的一部分。在这种情况下，该模板方法和公共接口方法都可以使用相同实现。</p>
	 *
	 * @param beanName the name of the bean to find a definition for
	 *                 -- 用于查找定义的bean的名称
	 * @return the BeanDefinition for this prototype name (never {@code null})
	 * -- 此原型名称的BeanDefinition(永远不为{@code null})
	 * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException if the bean definition cannot be resolved
	 *                                                                         -- 如果无法解析bean定义
	 * @throws BeansException                                                  in case of errors -- 如果有错误
	 * @see RootBeanDefinition
	 * @see ChildBeanDefinition
	 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#getBeanDefinition
	 */
	protected abstract BeanDefinition getBeanDefinition(String beanName) throws BeansException;

	/**
	 * Create a bean instance for the given merged bean definition (and arguments).
	 * <p>为给定的合并后BeanDefinition(和参数)创建一个bean实例</p>
	 * The bean definition will already have been merged with the parent definition
	 * in case of a child definition.
	 * <p>如果是子定义，则BeanDefinition将已经与父定义合并</p>
	 * <p>All bean retrieval methods delegate to this method for actual bean creation.
	 * <p>所有bean检索方法都委托方法进行实际的bean创建</p>
	 *
	 * @param beanName the name of the bean -- bean名
	 * @param mbd      the merged bean definition for the bean -- bean的合并后BeanDefinition
	 * @param args     explicit arguments to use for constructor or factory method invocation
	 *                 -- 用于构造函数或工厂方法调用的显示参数
	 * @return a new instance of the bean -- Bean新实例
	 * @throws BeanCreationException if the bean could not be created -- 如果无法创建该bean
	 */
	protected abstract Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
			throws BeanCreationException;


	/**
	 * CopyOnWriteArrayList which resets the beanPostProcessorCache field on modification.
	 *
	 * @since 5.3
	 */
	@SuppressWarnings("serial")
	private class BeanPostProcessorCacheAwareList extends CopyOnWriteArrayList<BeanPostProcessor> {

		@Override
		public BeanPostProcessor set(int index, BeanPostProcessor element) {
			BeanPostProcessor result = super.set(index, element);
			resetBeanPostProcessorCache();
			return result;
		}

		@Override
		public boolean add(BeanPostProcessor o) {
			boolean success = super.add(o);
			resetBeanPostProcessorCache();
			return success;
		}

		@Override
		public void add(int index, BeanPostProcessor element) {
			super.add(index, element);
			resetBeanPostProcessorCache();
		}

		@Override
		public BeanPostProcessor remove(int index) {
			BeanPostProcessor result = super.remove(index);
			resetBeanPostProcessorCache();
			return result;
		}

		@Override
		public boolean remove(Object o) {
			boolean success = super.remove(o);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean removeAll(Collection<?> c) {
			boolean success = super.removeAll(c);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean retainAll(Collection<?> c) {
			boolean success = super.retainAll(c);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean addAll(Collection<? extends BeanPostProcessor> c) {
			boolean success = super.addAll(c);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean addAll(int index, Collection<? extends BeanPostProcessor> c) {
			boolean success = super.addAll(index, c);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public boolean removeIf(Predicate<? super BeanPostProcessor> filter) {
			boolean success = super.removeIf(filter);
			if (success) {
				resetBeanPostProcessorCache();
			}
			return success;
		}

		@Override
		public void replaceAll(UnaryOperator<BeanPostProcessor> operator) {
			super.replaceAll(operator);
			resetBeanPostProcessorCache();
		}
	}


	/**
	 * Internal cache of pre-filtered post-processors.
	 *
	 * @since 5.3
	 */
	static class BeanPostProcessorCache {

		final List<InstantiationAwareBeanPostProcessor> instantiationAware = new ArrayList<>();

		final List<SmartInstantiationAwareBeanPostProcessor> smartInstantiationAware = new ArrayList<>();

		final List<DestructionAwareBeanPostProcessor> destructionAware = new ArrayList<>();

		final List<MergedBeanDefinitionPostProcessor> mergedDefinition = new ArrayList<>();
	}

}
