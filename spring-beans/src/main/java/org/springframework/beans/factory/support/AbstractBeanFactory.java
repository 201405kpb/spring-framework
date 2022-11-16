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

import java.beans.PropertyEditor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.PropertyEditorRegistrySupport;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanIsAbstractException;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.*;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.core.AttributeAccessor;
import org.springframework.core.DecoratingClassLoader;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.log.LogMessage;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Abstract base class for {@link org.springframework.beans.factory.BeanFactory}
 * implementations, providing the full capabilities of the
 * {@link org.springframework.beans.factory.config.ConfigurableBeanFactory} SPI.
 * Does <i>not</i> assume a listable bean factory: can therefore also be used
 * as base class for bean factory implementations which obtain bean definitions
 * from some backend resource (where bean definition access is an expensive operation).
 *
 * <p>This class provides a singleton cache (through its base class
 * {@link org.springframework.beans.factory.support.DefaultSingletonBeanRegistry},
 * singleton/prototype determination, {@link org.springframework.beans.factory.FactoryBean}
 * handling, aliases, bean definition merging for child bean definitions,
 * and bean destruction ({@link org.springframework.beans.factory.DisposableBean}
 * interface, custom destroy methods). Furthermore, it can manage a bean factory
 * hierarchy (delegating to the parent in case of an unknown bean), through implementing
 * the {@link org.springframework.beans.factory.HierarchicalBeanFactory} interface.
 *
 * <p>The main template methods to be implemented by subclasses are
 * {@link #getBeanDefinition} and {@link #createBean}, retrieving a bean definition
 * for a given bean name and creating a bean instance for a given bean definition,
 * respectively. Default implementations of those operations can be found in
 * {@link DefaultListableBeanFactory} and {@link AbstractAutowireCapableBeanFactory}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @author Sam Brannen
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
	 */
	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	/**
	 * ClassLoader to temporarily resolve bean class names with, if necessary.
	 */
	@Nullable
	private ClassLoader tempClassLoader;

	/**
	 * Whether to cache bean metadata or rather reobtain it for every access.
	 */
	private boolean cacheBeanMetadata = true;

	/**
	 * Resolution strategy for expressions in bean definition values.
	 */
	@Nullable
	private BeanExpressionResolver beanExpressionResolver;

	/**
	 * Spring ConversionService to use instead of PropertyEditors.
	 */
	@Nullable
	private ConversionService conversionService;

	/**
	 * Custom PropertyEditorRegistrars to apply to the beans of this factory.
	 */
	private final Set<PropertyEditorRegistrar> propertyEditorRegistrars = new LinkedHashSet<>(4);

	/**
	 * Custom PropertyEditors to apply to the beans of this factory.
	 */
	private final Map<Class<?>, Class<? extends PropertyEditor>> customEditors = new HashMap<>(4);

	/**
	 * A custom TypeConverter to use, overriding the default PropertyEditor mechanism.
	 */
	@Nullable
	private TypeConverter typeConverter;

	/**
	 * String resolvers to apply e.g. to annotation attribute values.
	 */
	private final List<StringValueResolver> embeddedValueResolvers = new CopyOnWriteArrayList<>();

	/**
	 * BeanPostProcessors to apply.
	 */
	private final List<BeanPostProcessor> beanPostProcessors = new BeanPostProcessorCacheAwareList();

	/**
	 * Cache of pre-filtered post-processors.
	 */
	@Nullable
	private BeanPostProcessorCache beanPostProcessorCache;

	/**
	 * Map from scope identifier String to corresponding Scope.
	 */
	private final Map<String, Scope> scopes = new LinkedHashMap<>(8);

	/**
	 * Map from bean name to merged RootBeanDefinition.
	 */
	private final Map<String, RootBeanDefinition> mergedBeanDefinitions = new ConcurrentHashMap<>(256);

	/**
	 * Names of beans that have already been created at least once.
	 */
	private final Set<String> alreadyCreated = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	/**
	 * Names of beans that are currently in creation.
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
	 *
	 * @param name         the name of the bean to retrieve
	 * @param requiredType the required type of the bean to retrieve
	 * @param args         arguments to use when creating a bean instance using explicit arguments
	 *                     (only applied when creating a new instance as opposed to retrieving an existing one)
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
	 */
	public <T> T getBean(String name, @Nullable Class<T> requiredType, @Nullable Object... args)
			throws BeansException {

		return doGetBean(name, requiredType, args, false);
	}

	/**
	 * Return an instance, which may be shared or independent, of the specified bean.
	 * 真正实现向IOC容器获取Bean的功能，也是触发依赖注入功能的地方
	 *
	 * @param name          the name of the bean to retrieve 要获取 Bean 的名字
	 * @param requiredType  the required type of the bean to retrieve 要获取 bean 的类型
	 * @param args          arguments to use when creating a bean instance using explicit arguments 创建 Bean 时传递的参数
	 *                      (only applied when creating a new instance as opposed to retrieving an existing one)
	 * @param typeCheckOnly whether the instance is obtained for a type check,not for actual use 是否为类型检查
	 * @return an instance of the bean
	 * @throws BeansException if the bean could not be created
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

	@Override
	public boolean containsBean(String name) {
		String beanName = transformedBeanName(name);
		if (containsSingleton(beanName) || containsBeanDefinition(beanName)) {
			return (!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(name));
		}
		// Not found -> check parent.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		return (parentBeanFactory != null && parentBeanFactory.containsBean(originalBeanName(name)));
	}

	@Override
	public boolean isSingleton(String name) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null) {
			if (beanInstance instanceof FactoryBean<?> factoryBean) {
				return (BeanFactoryUtils.isFactoryDereference(name) || factoryBean.isSingleton());
			} else {
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		}

		// No singleton instance found -> check bean definition.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isSingleton(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

		// In case of FactoryBean, return singleton status of created object if not a dereference.
		if (mbd.isSingleton()) {
			if (isFactoryBean(beanName, mbd)) {
				if (BeanFactoryUtils.isFactoryDereference(name)) {
					return true;
				}
				FactoryBean<?> factoryBean = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
				return factoryBean.isSingleton();
			} else {
				return !BeanFactoryUtils.isFactoryDereference(name);
			}
		} else {
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
	 * Internal extended variant of {@link #isTypeMatch(String, ResolvableType)}
	 * to check whether the bean with the given name matches the specified type. Allow
	 * additional constraints to be applied to ensure that beans are not created early.
	 *
	 * @param name        the name of the bean to query
	 * @param typeToMatch the type to match against (as a
	 *                    {@code ResolvableType})
	 * @return {@code true} if the bean type matches, {@code false} if it
	 * doesn't match or cannot be determined yet
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @see #getBean
	 * @see #getType
	 * @since 5.2
	 */
	protected boolean isTypeMatch(String name, ResolvableType typeToMatch, boolean allowFactoryBeanInit)
			throws NoSuchBeanDefinitionException {

		String beanName = transformedBeanName(name);
		boolean isFactoryDereference = BeanFactoryUtils.isFactoryDereference(name);

		// Check manually registered singletons.
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			if (beanInstance instanceof FactoryBean<?> factoryBean) {
				if (!isFactoryDereference) {
					Class<?> type = getTypeForFactoryBean(factoryBean);
					return (type != null && typeToMatch.isAssignableFrom(type));
				} else {
					return typeToMatch.isInstance(beanInstance);
				}
			} else if (!isFactoryDereference) {
				if (typeToMatch.isInstance(beanInstance)) {
					// Direct match for exposed instance?
					return true;
				} else if (typeToMatch.hasGenerics() && containsBeanDefinition(beanName)) {
					// Generics potentially only match on the target class, not on the proxy...
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					Class<?> targetType = mbd.getTargetType();
					if (targetType != null && targetType != ClassUtils.getUserClass(beanInstance)) {
						// Check raw class match as well, making sure it's exposed on the proxy.
						Class<?> classToMatch = typeToMatch.resolve();
						if (classToMatch != null && !classToMatch.isInstance(beanInstance)) {
							return false;
						}
						if (typeToMatch.isAssignableFrom(targetType)) {
							return true;
						}
					}
					ResolvableType resolvableType = mbd.targetType;
					if (resolvableType == null) {
						resolvableType = mbd.factoryMethodReturnType;
					}
					return (resolvableType != null && typeToMatch.isAssignableFrom(resolvableType));
				}
			}
			return false;
		} else if (containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			// null instance registered
			return false;
		}

		// No singleton instance found -> check bean definition.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.isTypeMatch(originalBeanName(name), typeToMatch);
		}

		// Retrieve corresponding bean definition.
		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();

		// Set up the types that we want to match against
		Class<?> classToMatch = typeToMatch.resolve();
		if (classToMatch == null) {
			classToMatch = FactoryBean.class;
		}
		Class<?>[] typesToMatch = (FactoryBean.class == classToMatch ?
				new Class<?>[]{classToMatch} : new Class<?>[]{FactoryBean.class, classToMatch});


		// Attempt to predict the bean type
		Class<?> predictedType = null;

		// We're looking for a regular reference, but we're a factory bean that has
		// a decorated bean definition. The target bean should be the same type
		// as FactoryBean would ultimately return.
		if (!isFactoryDereference && dbd != null && isFactoryBean(beanName, mbd)) {
			// We should only attempt if the user explicitly set lazy-init to true
			// and we know the merged bean definition is for a factory bean.
			if (!mbd.isLazyInit() || allowFactoryBeanInit) {
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				Class<?> targetType = predictBeanType(dbd.getBeanName(), tbd, typesToMatch);
				if (targetType != null && !FactoryBean.class.isAssignableFrom(targetType)) {
					predictedType = targetType;
				}
			}
		}

		// If we couldn't use the target type, try regular prediction.
		if (predictedType == null) {
			predictedType = predictBeanType(beanName, mbd, typesToMatch);
			if (predictedType == null) {
				return false;
			}
		}

		// Attempt to get the actual ResolvableType for the bean.
		ResolvableType beanType = null;

		// If it's a FactoryBean, we want to look at what it creates, not the factory class.
		if (FactoryBean.class.isAssignableFrom(predictedType)) {
			if (beanInstance == null && !isFactoryDereference) {
				beanType = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit);
				predictedType = beanType.resolve();
				if (predictedType == null) {
					return false;
				}
			}
		} else if (isFactoryDereference) {
			// Special case: A SmartInstantiationAwareBeanPostProcessor returned a non-FactoryBean
			// type, but we nevertheless are being asked to dereference a FactoryBean...
			// Let's check the original bean class and proceed with it if it is a FactoryBean.
			predictedType = predictBeanType(beanName, mbd, FactoryBean.class);
			if (predictedType == null || !FactoryBean.class.isAssignableFrom(predictedType)) {
				return false;
			}
		}

		// We don't have an exact type but if bean definition target type or the factory
		// method return type matches the predicted type then we can use that.
		if (beanType == null) {
			ResolvableType definedType = mbd.targetType;
			if (definedType == null) {
				definedType = mbd.factoryMethodReturnType;
			}
			if (definedType != null && definedType.resolve() == predictedType) {
				beanType = definedType;
			}
		}

		// If we have a bean type use it so that generics are considered
		if (beanType != null) {
			return typeToMatch.isAssignableFrom(beanType);
		}

		// If we don't have a bean type, fallback to the predicted type
		return typeToMatch.isAssignableFrom(predictedType);
	}

	@Override
	public boolean isTypeMatch(String name, Class<?> typeToMatch) throws NoSuchBeanDefinitionException {
		return isTypeMatch(name, ResolvableType.forRawClass(typeToMatch));
	}

	@Override
	@Nullable
	public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		return getType(name, true);
	}

	@Override
	@Nullable
	public Class<?> getType(String name, boolean allowFactoryBeanInit) throws NoSuchBeanDefinitionException {
		String beanName = transformedBeanName(name);

		// Check manually registered singletons.
		Object beanInstance = getSingleton(beanName, false);
		if (beanInstance != null && beanInstance.getClass() != NullBean.class) {
			if (beanInstance instanceof FactoryBean<?> factoryBean && !BeanFactoryUtils.isFactoryDereference(name)) {
				return getTypeForFactoryBean(factoryBean);
			} else {
				return beanInstance.getClass();
			}
		}

		// No singleton instance found -> check bean definition.
		BeanFactory parentBeanFactory = getParentBeanFactory();
		if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
			// No bean definition found in this factory -> delegate to parent.
			return parentBeanFactory.getType(originalBeanName(name));
		}

		RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
		Class<?> beanClass = predictBeanType(beanName, mbd);

		if (beanClass != null) {
			// Check bean class whether we're dealing with a FactoryBean.
			if (FactoryBean.class.isAssignableFrom(beanClass)) {
				if (!BeanFactoryUtils.isFactoryDereference(name)) {
					// If it's a FactoryBean, we want to look at what it creates, not at the factory class.
					beanClass = getTypeForFactoryBean(beanName, mbd, allowFactoryBeanInit).resolve();
				}
			} else if (BeanFactoryUtils.isFactoryDereference(name)) {
				return null;
			}
		}

		if (beanClass == null) {
			// Check decorated bean definition, if any: We assume it'll be easier
			// to determine the decorated bean's type than the proxy's type.
			BeanDefinitionHolder dbd = mbd.getDecoratedDefinition();
			if (dbd != null && !BeanFactoryUtils.isFactoryDereference(name)) {
				RootBeanDefinition tbd = getMergedBeanDefinition(dbd.getBeanName(), dbd.getBeanDefinition(), mbd);
				Class<?> targetClass = predictBeanType(dbd.getBeanName(), tbd);
				if (targetClass != null && !FactoryBean.class.isAssignableFrom(targetClass)) {
					return targetClass;
				}
			}
		}

		return beanClass;
	}

	@Override
	public String[] getAliases(String name) {
		String beanName = transformedBeanName(name);
		List<String> aliases = new ArrayList<>();
		boolean factoryPrefix = name.startsWith(FACTORY_BEAN_PREFIX);
		String fullBeanName = beanName;
		if (factoryPrefix) {
			fullBeanName = FACTORY_BEAN_PREFIX + beanName;
		}
		if (!fullBeanName.equals(name)) {
			aliases.add(fullBeanName);
		}
		String[] retrievedAliases = super.getAliases(beanName);
		String prefix = factoryPrefix ? FACTORY_BEAN_PREFIX : "";
		for (String retrievedAlias : retrievedAliases) {
			String alias = prefix + retrievedAlias;
			if (!alias.equals(name)) {
				aliases.add(alias);
			}
		}
		if (!containsSingleton(beanName) && !containsBeanDefinition(beanName)) {
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null) {
				aliases.addAll(Arrays.asList(parentBeanFactory.getAliases(fullBeanName)));
			}
		}
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

	@Override
	public boolean containsLocalBean(String name) {
		String beanName = transformedBeanName(name);
		return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
				(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableBeanFactory interface
	//---------------------------------------------------------------------

	@Override
	public void setParentBeanFactory(@Nullable BeanFactory parentBeanFactory) {
		if (this.parentBeanFactory != null && this.parentBeanFactory != parentBeanFactory) {
			throw new IllegalStateException("Already associated with parent BeanFactory: " + this.parentBeanFactory);
		}
		if (this == parentBeanFactory) {
			throw new IllegalStateException("Cannot set parent bean factory to self");
		}
		this.parentBeanFactory = parentBeanFactory;
	}

	@Override
	public void setBeanClassLoader(@Nullable ClassLoader beanClassLoader) {
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
	 *
	 * @return the custom TypeConverter, or {@code null} if none specified
	 */
	@Nullable
	protected TypeConverter getCustomTypeConverter() {
		return this.typeConverter;
	}

	@Override
	public TypeConverter getTypeConverter() {
		TypeConverter customConverter = getCustomTypeConverter();
		if (customConverter != null) {
			return customConverter;
		} else {
			// Build default TypeConverter, registering custom editors.
			SimpleTypeConverter typeConverter = new SimpleTypeConverter();
			typeConverter.setConversionService(getConversionService());
			registerCustomEditors(typeConverter);
			return typeConverter;
		}
	}

	@Override
	public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		this.embeddedValueResolvers.add(valueResolver);
	}

	@Override
	public boolean hasEmbeddedValueResolver() {
		return !this.embeddedValueResolvers.isEmpty();
	}

	@Override
	@Nullable
	public String resolveEmbeddedValue(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String result = value;
		for (StringValueResolver resolver : this.embeddedValueResolvers) {
			result = resolver.resolveStringValue(result);
			if (result == null) {
				return null;
			}
		}
		return result;
	}

	@Override
	public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {
		Assert.notNull(beanPostProcessor, "BeanPostProcessor must not be null");
		synchronized (this.beanPostProcessors) {
			// Remove from old position, if any
			this.beanPostProcessors.remove(beanPostProcessor);
			// Add to end of list
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

	@Override
	@Nullable
	public Scope getRegisteredScope(String scopeName) {
		Assert.notNull(scopeName, "Scope identifier must not be null");
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
	 * 用于检索的合并bean定义的 bean 的名称（可能是别名）
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * 一个已经合并完毕(如果存在父bean)的RootBeanDefinition类型的bean定义
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * 如果没有给定名称的 bean
	 * @throws BeanDefinitionStoreException  in case of an invalid bean definition
	 * 无效的 bean 定义
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
	 * 确定给定名称的 bean 是否是FactoryBean
	 * @param name 要检查的bean name
	 * @return bean 是否是 FactoryBean
	 */
	@Override
	public boolean isFactoryBean(String name) throws NoSuchBeanDefinitionException {
		//获取转换后的名字
		String beanName = transformedBeanName(name);
		Object beanInstance = getSingleton(beanName, false);
		//如果已经是单例，就判断是否是FactoryBean类型
		if (beanInstance != null) {
			return (beanInstance instanceof FactoryBean);
		}
		// No singleton instance found -> check bean definition.
		// 没有单例，看是否有bean定义，没有就看父类bean工厂
		if (!containsBeanDefinition(beanName) && getParentBeanFactory() instanceof ConfigurableBeanFactory cbf) {
			// No bean definition found in this factory -> delegate to parent.
			return cbf.isFactoryBean(name);
		}
		return isFactoryBean(beanName, getMergedLocalBeanDefinition(beanName));
	}

	@Override
	public boolean isActuallyInCreation(String beanName) {
		return (isSingletonCurrentlyInCreation(beanName) || isPrototypeCurrentlyInCreation(beanName));
	}

	/**
	 * Return whether the specified prototype bean is currently in creation
	 * (within the current thread).
	 *
	 * @param beanName the name of the bean
	 */
	protected boolean isPrototypeCurrentlyInCreation(String beanName) {
		Object curVal = this.prototypesCurrentlyInCreation.get();
		return (curVal != null &&
				(curVal.equals(beanName) || (curVal instanceof Set<?> set && set.contains(beanName))));
	}

	/**
	 * Callback before prototype creation.
	 * 在原型bean实例创建之前进行回调
	 * <p>The default implementation registers the prototype as currently in creation.
	 * 默认实现是将当前原型beanName注册到当前正在创建的bean缓存prototypesCurrentlyInCreation中
	 *
	 * @param beanName the name of the prototype about to be created
	 * 即将创建的原型bean的名称
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
	 * Callback after prototype creation.
	 * 在原型bean实例创建之后进行回调
	 * <p>The default implementation marks the prototype as not in creation anymore.
	 * 默认实现将原型标记为不在创建中，即从当前正在创建的bean缓存prototypesCurrentlyInCreation中移除
	 *
	 * @param beanName the name of the prototype that has been created
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
	 * Return the bean name, stripping out the factory dereference prefix if necessary,
	 * and resolving aliases to canonical names.
	 *
	 * @param name the user-specified name
	 * @return the transformed bean name
	 */
	protected String transformedBeanName(String name) {
		return canonicalName(BeanFactoryUtils.transformedBeanName(name));
	}

	/**
	 * Determine the original bean name, resolving locally defined aliases to canonical names.
	 * 确定原始 bean 名称，将本地定义的别名解析为规范名称
	 *
	 * @param name the user-specified name
	 * @return the original bean name
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
	 * Initialize the given BeanWrapper with the custom editors registered
	 * with this factory. To be called for BeanWrappers that will create
	 * and populate bean instances.
	 * 使用在此工厂注册的自定义编辑器初始化给定的 BeanWrapper。为创建和填充 Bean 实例的 BeanWrapper 调用。
	 * <p>The default implementation delegates to {@link #registerCustomEditors}.
	 * Can be overridden in subclasses.
	 *
	 * @param bw the BeanWrapper to initialize
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
	 * Initialize the given PropertyEditorRegistry with the custom editors
	 * that have been registered with this BeanFactory.
	 * 使用已在此 BeanFactory 注册的自定义编辑器初始化给定的PropertyEditorRegistry。
	 * <p>To be called for BeanWrappers that will create and populate bean
	 * instances, and for SimpleTypeConverter used for constructor argument
	 * and factory method type conversion.
	 * <p>为创建和填充 Bean 实例的 BeanWrapper 调用，以及用于构造器参数和工厂方法类型转换的SimpleTypeConverter
	 *
	 * @param registry the PropertyEditorRegistry to initialize
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
	 * Return a merged RootBeanDefinition, traversing the parent bean definition
	 * if the specified bean corresponds to a child bean definition.
	 * 返回给定bean的的RootBeanDefinition，如果给定的 bean 具有父类 bean 定义，则遍历、合并所有父类 bean 定义
	 *
	 * @param beanName the name of the bean to retrieve the merged definition for
	 * 要检索合并定义的子类 bean 的名称
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * 一个已经合并完毕(如果存在父bean)的RootBeanDefinition类型的bean定义
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * @throws BeanDefinitionStoreException  in case of an invalid bean definition
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
	 * Return a RootBeanDefinition for the given top-level bean, by merging with
	 * the parent if the given bean's definition is a child bean definition.
	 *返回给定顶级 bean 的RootBeanDefinition，如果给定 bean是子bean 定义，则它将会与父bean合并然后返回
	 * @param beanName the name of the bean definition
	 * bean定义的名称
	 * @param bd       the original bean definition (Root/ChildBeanDefinition)
	 * 当前bean的原始bean定义
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * 一个已经合并完毕(如果存在父bean)的RootBeanDefinition类型的bean定义
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition bd)
			throws BeanDefinitionStoreException {

		return getMergedBeanDefinition(beanName, bd, null);
	}

	/**
	 * Return a RootBeanDefinition for the given bean, by merging with the
	 * parent if the given bean's definition is a child bean definition.
	 * 如果不存在parentName，即不需要被合并，直接将bd转为RootBeanDefinition 返回即可
	 * 如果存在parentName
	 * <ul>
	 *     <li>先根据parentName 找到父bd，若实体存在多级继承关系，则需要递归地查找。</li>
	 *     <li>将父bd转为RootBeanDefinition，并将子bd与父bd进行合并</li>
	 *     <li>设置一些其他属性</li>
	 * </ul>
	 * @param beanName     the name of the bean definition
	 * @param bd           the original bean definition (Root/ChildBeanDefinition)
	 * @param containingBd the containing bean definition in case of inner bean,
	 *                     or {@code null} in case of a top-level bean
	 * @return a (potentially merged) RootBeanDefinition for the given bean
	 * @throws BeanDefinitionStoreException in case of an invalid bean definition
	 */
	protected RootBeanDefinition getMergedBeanDefinition(
			String beanName, BeanDefinition bd, @Nullable BeanDefinition containingBd)
			throws BeanDefinitionStoreException {

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
				if (containingBd != null && !containingBd.isSingleton() && mbd.isSingleton()) {
					mbd.setScope(containingBd.getScope());
				}

				// Cache the merged bean definition for the time being
				// (it might still get re-merged later on in order to pick up metadata changes)
				if (containingBd == null && isCacheBeanMetadata()) {
					this.mergedBeanDefinitions.put(beanName, mbd);
				}
			}
			if (previous != null) {
				copyRelevantMergedBeanDefinitionCaches(previous, mbd);
			}
			return mbd;
		}
	}

	private void copyRelevantMergedBeanDefinitionCaches(RootBeanDefinition previous, RootBeanDefinition mbd) {
		if (ObjectUtils.nullSafeEquals(mbd.getBeanClassName(), previous.getBeanClassName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryBeanName(), previous.getFactoryBeanName()) &&
				ObjectUtils.nullSafeEquals(mbd.getFactoryMethodName(), previous.getFactoryMethodName())) {
			ResolvableType targetType = mbd.targetType;
			ResolvableType previousTargetType = previous.targetType;
			if (targetType == null || targetType.equals(previousTargetType)) {
				mbd.targetType = previousTargetType;
				mbd.isFactoryBean = previous.isFactoryBean;
				mbd.resolvedTargetType = previous.resolvedTargetType;
				mbd.factoryMethodReturnType = previous.factoryMethodReturnType;
				mbd.factoryMethodToIntrospect = previous.factoryMethodToIntrospect;
			}
		}
	}

	/**
	 * Check the given merged bean definition,
	 * potentially throwing validation exceptions.
	 *
	 * @param mbd      the merged bean definition to check
	 * @param beanName the name of the bean
	 * @param args     the arguments for bean creation, if any
	 * @throws BeanDefinitionStoreException in case of validation failure
	 */
	protected void checkMergedBeanDefinition(RootBeanDefinition mbd, String beanName, @Nullable Object[] args)
			throws BeanDefinitionStoreException {

		if (mbd.isAbstract()) {
			throw new BeanIsAbstractException(beanName);
		}
	}

	/**
	 * Remove the merged bean definition for the specified bean,
	 * recreating it on next access.
	 *
	 * @param beanName the bean name to clear the merged definition for
	 */
	protected void clearMergedBeanDefinition(String beanName) {
		RootBeanDefinition bd = this.mergedBeanDefinitions.get(beanName);
		if (bd != null) {
			bd.stale = true;
		}
	}

	/**
	 * Clear the merged bean definition cache, removing entries for beans
	 * which are not considered eligible for full metadata caching yet.
	 * <p>Typically triggered after changes to the original bean definitions,
	 * e.g. after applying a {@code BeanFactoryPostProcessor}. Note that metadata
	 * for beans which have already been created at this point will be kept around.
	 *
	 * @since 4.2
	 */
	public void clearMetadataCache() {
		this.mergedBeanDefinitions.forEach((beanName, bd) -> {
			if (!isBeanEligibleForMetadataCaching(beanName)) {
				bd.stale = true;
			}
		});
	}

	/**
	 * Resolve the bean class for the specified bean definition,
	 * resolving a bean class name into a Class reference (if necessary)
	 * and storing the resolved Class in the bean definition for further use.
	 *
	 * @param mbd          the merged bean definition to determine the class for
	 * @param beanName     the name of the bean (for error handling purposes)
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 *                     (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the resolved bean class (or {@code null} if none)
	 * @throws CannotLoadBeanClassException if we failed to load the class
	 */
	@Nullable
	protected Class<?> resolveBeanClass(RootBeanDefinition mbd, String beanName, Class<?>... typesToMatch)
			throws CannotLoadBeanClassException {
		try {
			if (mbd.hasBeanClass()) {
				return mbd.getBeanClass();
			}
			return doResolveBeanClass(mbd, typesToMatch);
		} catch (ClassNotFoundException ex) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), ex);
		} catch (LinkageError err) {
			throw new CannotLoadBeanClassException(mbd.getResourceDescription(), beanName, mbd.getBeanClassName(), err);
		}
	}

	@Nullable
	private Class<?> doResolveBeanClass(RootBeanDefinition mbd, Class<?>... typesToMatch)
			throws ClassNotFoundException {

		ClassLoader beanClassLoader = getBeanClassLoader();
		ClassLoader dynamicLoader = beanClassLoader;
		boolean freshResolve = false;

		if (!ObjectUtils.isEmpty(typesToMatch)) {
			// When just doing type checks (i.e. not creating an actual instance yet),
			// use the specified temporary class loader (e.g. in a weaving scenario).
			ClassLoader tempClassLoader = getTempClassLoader();
			if (tempClassLoader != null) {
				dynamicLoader = tempClassLoader;
				freshResolve = true;
				if (tempClassLoader instanceof DecoratingClassLoader dcl) {
					for (Class<?> typeToMatch : typesToMatch) {
						dcl.excludeClass(typeToMatch.getName());
					}
				}
			}
		}

		String className = mbd.getBeanClassName();
		if (className != null) {
			Object evaluated = evaluateBeanDefinitionString(className, mbd);
			if (!className.equals(evaluated)) {
				// A dynamically resolved expression, supported as of 4.2...
				if (evaluated instanceof Class<?> clazz) {
					return clazz;
				} else if (evaluated instanceof String str) {
					className = str;
					freshResolve = true;
				} else {
					throw new IllegalStateException("Invalid class name expression result: " + evaluated);
				}
			}
			if (freshResolve) {
				// When resolving against a temporary class loader, exit early in order
				// to avoid storing the resolved Class in the bean definition.
				if (dynamicLoader != null) {
					try {
						return dynamicLoader.loadClass(className);
					} catch (ClassNotFoundException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Could not load class [" + className + "] from " + dynamicLoader + ": " + ex);
						}
					}
				}
				return ClassUtils.forName(className, dynamicLoader);
			}
		}

		// Resolve regularly, caching the result in the BeanDefinition...
		return mbd.resolveBeanClass(beanClassLoader);
	}

	/**
	 * Evaluate the given String as contained in a bean definition,
	 * potentially resolving it as an expression.
	 *
	 * @param value          the value to check
	 * @param beanDefinition the bean definition that the value comes from
	 * @return the resolved value
	 * @see #setBeanExpressionResolver
	 */
	@Nullable
	protected Object evaluateBeanDefinitionString(@Nullable String value, @Nullable BeanDefinition beanDefinition) {
		if (this.beanExpressionResolver == null) {
			return value;
		}

		Scope scope = null;
		if (beanDefinition != null) {
			String scopeName = beanDefinition.getScope();
			if (scopeName != null) {
				scope = getRegisteredScope(scopeName);
			}
		}
		return this.beanExpressionResolver.evaluate(value, new BeanExpressionContext(this, scope));
	}


	/**
	 * Predict the eventual bean type (of the processed bean instance) for the
	 * specified bean. Called by {@link #getType} and {@link #isTypeMatch}.
	 * Does not need to handle FactoryBeans specifically, since it is only
	 * supposed to operate on the raw bean type.
	 * <p>This implementation is simplistic in that it is not able to
	 * handle factory methods and InstantiationAwareBeanPostProcessors.
	 * It only predicts the bean type correctly for a standard bean.
	 * To be overridden in subclasses, applying more sophisticated type detection.
	 *
	 * @param beanName     the name of the bean
	 * @param mbd          the merged bean definition to determine the type for
	 * @param typesToMatch the types to match in case of internal type matching purposes
	 *                     (also signals that the returned {@code Class} will never be exposed to application code)
	 * @return the type of the bean, or {@code null} if not predictable
	 */
	@Nullable
	protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
		Class<?> targetType = mbd.getTargetType();
		if (targetType != null) {
			return targetType;
		}
		if (mbd.getFactoryMethodName() != null) {
			return null;
		}
		return resolveBeanClass(mbd, beanName, typesToMatch);
	}

	/**
	 * Check whether the given bean is defined as a {@link FactoryBean}.
	 * 通过该beanName的MergedBeanDefinition来检查对应的Bean是否为FactoryBean
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the corresponding bean definition
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
	 * Determine the bean type for the given FactoryBean definition, as far as possible.
	 * Only called if there is no singleton instance registered for the target bean
	 * already. The implementation is allowed to instantiate the target factory bean if
	 * {@code allowInit} is {@code true} and the type cannot be determined another way;
	 * otherwise it is restricted to introspecting signatures and related metadata.
	 * <p>If no {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} if set on the bean definition
	 * and {@code allowInit} is {@code true}, the default implementation will create
	 * the FactoryBean via {@code getBean} to call its {@code getObjectType} method.
	 * Subclasses are encouraged to optimize this, typically by inspecting the generic
	 * signature of the factory bean class or the factory method that creates it.
	 * If subclasses do instantiate the FactoryBean, they should consider trying the
	 * {@code getObjectType} method without fully populating the bean. If this fails,
	 * a full FactoryBean creation as performed by this implementation should be used
	 * as fallback.
	 *
	 * @param beanName  the name of the bean
	 * @param mbd       the merged bean definition for the bean
	 * @param allowInit if initialization of the FactoryBean is permitted if the type
	 *                  cannot be determined another way
	 * @return the type for the bean if determinable, otherwise {@code ResolvableType.NONE}
	 * @see org.springframework.beans.factory.FactoryBean#getObjectType()
	 * @see #getBean(String)
	 * @since 5.2
	 */
	protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
		ResolvableType result = getTypeForFactoryBeanFromAttributes(mbd);
		if (result != ResolvableType.NONE) {
			return result;
		}

		if (allowInit && mbd.isSingleton()) {
			try {
				FactoryBean<?> factoryBean = doGetBean(FACTORY_BEAN_PREFIX + beanName, FactoryBean.class, null, true);
				Class<?> objectType = getTypeForFactoryBean(factoryBean);
				return (objectType != null ? ResolvableType.forClass(objectType) : ResolvableType.NONE);
			} catch (BeanCreationException ex) {
				if (ex.contains(BeanCurrentlyInCreationException.class)) {
					logger.trace(LogMessage.format("Bean currently in creation on FactoryBean type check: %s", ex));
				} else if (mbd.isLazyInit()) {
					logger.trace(LogMessage.format("Bean creation exception on lazy FactoryBean type check: %s", ex));
				} else {
					logger.debug(LogMessage.format("Bean creation exception on eager FactoryBean type check: %s", ex));
				}
				onSuppressedException(ex);
			}
		}
		return ResolvableType.NONE;
	}

	/**
	 * Determine the bean type for a FactoryBean by inspecting its attributes for a
	 * {@link FactoryBean#OBJECT_TYPE_ATTRIBUTE} value.
	 *
	 * @param attributes the attributes to inspect
	 * @return a {@link ResolvableType} extracted from the attributes or
	 * {@code ResolvableType.NONE}
	 * @since 5.2
	 */
	ResolvableType getTypeForFactoryBeanFromAttributes(AttributeAccessor attributes) {
		Object attribute = attributes.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
		if (attribute instanceof ResolvableType resolvableType) {
			return resolvableType;
		}
		if (attribute instanceof Class<?> clazz) {
			return ResolvableType.forClass(clazz);
		}
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
	 *
	 * @param beanName the name of the bean
	 */
	protected void cleanupAfterBeanCreationFailure(String beanName) {
		synchronized (this.mergedBeanDefinitions) {
			this.alreadyCreated.remove(beanName);
		}
	}

	/**
	 * Determine whether the specified bean is eligible for having
	 * its bean definition metadata cached.
	 *
	 * @param beanName the name of the bean
	 * @return {@code true} if the bean's metadata may be cached
	 * at this point already
	 */
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return this.alreadyCreated.contains(beanName);
	}

	/**
	 * Remove the singleton instance (if any) for the given bean name,
	 * but only if it hasn't been used for other purposes than type checking.
	 *
	 * @param beanName the name of the bean
	 * @return {@code true} if actually removed, {@code false} otherwise
	 */
	protected boolean removeSingletonIfCreatedForTypeCheckOnly(String beanName) {
		if (!this.alreadyCreated.contains(beanName)) {
			removeSingleton(beanName);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Check whether this factory's bean creation phase already started,
	 * i.e. whether any bean has been marked as created in the meantime.
	 *
	 * @see #markBeanAsCreated
	 * @since 4.2.2
	 */
	protected boolean hasBeanCreationStarted() {
		return !this.alreadyCreated.isEmpty();
	}

	/**
	 * Get the object for the given bean instance, either the bean
	 * instance itself or its created object in case of a FactoryBean.
	 * 获取给定 bean 实例，在工厂Bean的情况下获取 bean 实例本身或其创建的对象。
	 *
	 * @param beanInstance the shared bean instance
	 * 共享的bean实例
	 * @param name         the name that may include factory dereference prefix
	 * 可能包含工厂&前缀的bean name，传递的数据
	 * @param beanName     the canonical bean name
	 * 规范的beanName
	 * @param mbd          the merged bean definition
	 * 已合并的bean定义，如果beanInstance此前就被创建了，那么mbd为null
	 * @return the object to expose for the bean
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
	 * Determine whether the given bean name is already in use within this factory,
	 * i.e. whether there is a local bean or alias registered under this name or
	 * an inner bean created with this name.
	 *
	 * @param beanName the name to check
	 */
	public boolean isBeanNameInUse(String beanName) {
		return isAlias(beanName) || containsLocalBean(beanName) || hasDependentBean(beanName);
	}

	/**
	 * Determine whether the given bean requires destruction on shutdown.
	 * <p>The default implementation checks the DisposableBean interface as well as
	 * a specified destroy method and registered DestructionAwareBeanPostProcessors.
	 *
	 * @param bean the bean instance to check
	 * @param mbd  the corresponding bean definition
	 * @see org.springframework.beans.factory.DisposableBean
	 * @see AbstractBeanDefinition#getDestroyMethodName()
	 * @see org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor
	 */
	protected boolean requiresDestruction(Object bean, RootBeanDefinition mbd) {
		return (bean.getClass() != NullBean.class && (DisposableBeanAdapter.hasDestroyMethod(bean, mbd) ||
				(hasDestructionAwareBeanPostProcessors() && DisposableBeanAdapter.hasApplicableProcessors(
						bean, getBeanPostProcessorCache().destructionAware))));
	}

	/**
	 * Add the given bean to the list of disposable beans in this factory,
	 * registering its DisposableBean interface and/or the given destroy method
	 * to be called on factory shutdown (if applicable). Only applies to singletons.
	 * 将符合条件的给定的 bean 添加到此工厂中的disposableBeans缓存中，仅适用于单例，在被容器销毁时会进行销毁回调
	 * @param beanName the name of the bean
	 * @param bean     the bean instance
	 * @param mbd      the bean definition for the bean
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
					throw new IllegalStateException("No Scope registered for scope name '" + mbd.getScope() + "'");
				}
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
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 *
	 * @param beanName the name of the bean to look for
	 * @return if this bean factory contains a bean definition with the given name
	 * @see #containsBean
	 * @see org.springframework.beans.factory.ListableBeanFactory#containsBeanDefinition
	 */
	protected abstract boolean containsBeanDefinition(String beanName);

	/**
	 * Return the bean definition for the given bean name.
	 * Subclasses should normally implement caching, as this method is invoked
	 * by this class every time bean definition metadata is needed.
	 * <p>Depending on the nature of the concrete bean factory implementation,
	 * this operation might be expensive (for example, because of directory lookups
	 * in external registries). However, for listable bean factories, this usually
	 * just amounts to a local hash lookup: The operation is therefore part of the
	 * public interface there. The same implementation can serve for both this
	 * template method and the public interface method in that case.
	 *
	 * @param beanName the name of the bean to find a definition for
	 * @return the BeanDefinition for this prototype name (never {@code null})
	 * @throws org.springframework.beans.factory.NoSuchBeanDefinitionException if the bean definition cannot be resolved
	 * @throws BeansException                                                  in case of errors
	 * @see RootBeanDefinition
	 * @see ChildBeanDefinition
	 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#getBeanDefinition
	 */
	protected abstract BeanDefinition getBeanDefinition(String beanName) throws BeansException;

	/**
	 * Create a bean instance for the given merged bean definition (and arguments).
	 * The bean definition will already have been merged with the parent definition
	 * in case of a child definition.
	 * <p>All bean retrieval methods delegate to this method for actual bean creation.
	 *
	 * @param beanName the name of the bean
	 * @param mbd      the merged bean definition for the bean
	 * @param args     explicit arguments to use for constructor or factory method invocation
	 * @return a new instance of the bean
	 * @throws BeanCreationException if the bean could not be created
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
