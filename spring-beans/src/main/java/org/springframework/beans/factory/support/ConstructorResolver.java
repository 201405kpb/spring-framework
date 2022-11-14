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

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanReference;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 *
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @author Phil Webb
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see #resolveConstructorOrFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	/**
	 * Marker for autowired arguments in a cached argument array, to be replaced
	 * by a {@linkplain #resolveAutowiredArgument resolved autowired argument}.
	 */
	private static final Object autowiredArgumentMarker = new Object();

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	// BeanWrapper-based construction

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified,
	 * matching all remaining arguments with beans from the bean factory.
	 * <p>This corresponds to constructor injection: In this mode, a Spring
	 * bean factory is able to host components that expect constructor-based
	 * dependency resolution.
	 * 选择合适的构造器实例化bean，并且进行构造器自动注入，返回被包装后的bean实例——BeanWrapper对象。
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			@Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {
		// 封装 BeanWrapperImpl 对象，并完成初始化
		BeanWrapperImpl bw = new BeanWrapperImpl();
		//初始化BeanWrapper，设置转换服务ConversionService，注册自定义的属性编辑器PropertyEditor
		this.beanFactory.initBeanWrapper(bw);
		// 最终使用的构造函数
		Constructor<?> constructorToUse = null;
		// 构造参数
		ArgumentsHolder argsHolderToUse = null;
		// 构造参数
		Object[] argsToUse = null;

		// 判断有无显式指定参数,如果有则优先使用,如 xxxBeanFactory.getBean("teacher", "李华",3);
		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		// 没有显式指定参数,则解析配置文件中的参数
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				// 优先尝试从缓存中获取,spring对参数的解析过程是比较复杂也耗时的,所以这里先尝试从缓存中获取已经解析过的构造函数参数
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				//如果构造方法和参数都不为Null
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					// 获取缓存中的构造参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			// 缓存中存在,则解析存储在 BeanDefinition 中的参数
			// 如给定方法的构造函数 A(int ,int )，则通过此方法后就会把配置文件中的("1","1")转换为 (1,1)
			// 缓存中的值可能是原始值也有可能是最终值
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}

		// 缓存不存在,则需要解析构造函数参数,以确定使用哪一个构造函数来进行实例化
		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			//保存候选构造器
			Constructor<?>[] candidates = chosenCtors;
			//如果候选构造器数组为null，那么主动获取全部构造器作为候选构造器数组
			//对于XML的配置，candidates就是null，基于注解的配置candidates可能不会为null
			if (candidates == null) {
				//获取beanClass
				Class<?> beanClass = mbd.getBeanClass();
				try {
					//isNonPublicAccessAllowed判断是否允许访问非公共的构造器和方法
					//允许的话就反射调用getDeclaredConstructors获取全部构造器，否则反射调用getConstructors获取公共的构造器
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				} catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
									"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}
			//如果仅有一个候选构造器，并且外部没有传递参数，并且没有定义< constructor-arg >标签
			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				//获取该构造器
				Constructor<?> uniqueCandidate = candidates[0];
				//如果该构造器没有参数，表示无参构造器，那简单，由于只有一个无参构造器
				//那么每一次创建对象都是调用这个构造器，所以将其加入缓存中
				if (uniqueCandidate.getParameterCount() == 0) {
					synchronized (mbd.constructorArgumentLock) {
						//设置已解析的构造器缓存属性为uniqueCandidate
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						//设置已解析的构造器参数标志位缓存属性为true
						mbd.constructorArgumentsResolved = true;
						//设置已解析的构造器参数缓存属性为EMPTY_ARGS，表示空参数
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					/*
					 * 到这一步，已经确定了构造器与参数，随后调用instantiate方法，
					 * 传递beanName、mbd、uniqueCandidate、EMPTY_ARGS初始化bean实例，随后设置到bw的相关属性中
					 */
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// Need to resolve the constructor.
			/*
			 * 如果候选构造器数组不为null，或者自动注入的模式为AUTOWIRE_CONSTRUCTOR，即构造器注入
			 * 那么需要自动装配，autowiring设置为true
			 */
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			// 用于承载解析后的构造函数参数的值
			ConstructorArgumentValues resolvedValues = null;

			//参数个数
			int minNrOfArgs;
			//如果外部传递进来的构造器参数数组不为null，那么minNrOfArgs等于explicitArgs长度
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				//获取bean定义中的constructorArgumentValues属性，前面解析标签的时候就说过了，这是对于全部<constructor-arg>子标签的解析时设置的属性
				//保存了基于XML的<constructor-arg>子标签传递的参数的名字、类型、索引、值等信息，对于基于注解的配置来说cargs中并没有保存信息，返回空对象
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				//创建一个新的ConstructorArgumentValues，用于保存解析后的构造器参数，解析就是将参数值转换为对应的类型
				resolvedValues = new ConstructorArgumentValues();
				//解析构造器参数，minNrOfArgs设置为解析构造器之后返回的参数个数，对于基于注解配置的配置来说将返回0
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}
			// 对所有构造函数进行排序,public 且 参数最多的构造函数会排在第一位
			AutowireUtils.sortConstructors(candidates);
			//最小的类型差异权重，值越小越匹配，初始化为int类型的最大值
			int minTypeDiffWeight = Integer.MAX_VALUE;
			//模棱两可的构造函数集合
			Set<Constructor<?>> ambiguousConstructors = null;
			Deque<UnsatisfiedDependencyException> causes = null;
			// 迭代所有构造函数，解析确定使用哪一个构造函数
			for (Constructor<?> candidate : candidates) {
				// 获取该构造函数的参数类型
				int parameterCount = candidate.getParameterCount();
				// 如果已经找到选用的构造函数或者需要的参数个数小于当前的构造函数参数个数，则终止。因为，已经按照参数个数降序排列了
				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					break;
				}
				// 参数个数不等，跳过
				if (parameterCount < minNrOfArgs) {
					continue;
				}

				// 参数持有者 ArgumentsHolder 对象
				ArgumentsHolder argsHolder;
				Class<?>[] paramTypes = candidate.getParameterTypes();
				/*如果resolvedValues不为null，表示已解析构造器参数*/
				if (resolvedValues != null) {
					try {
						// 获取注解上的参数名称 by @ConstructorProperties
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
						//如果获取的paramNames为null，表示该构造器上不存在@ConstructorProperties注解，基本上都会走这个逻辑
						if (paramNames == null) {
							//获取AbstractAutowireCapableBeanFactory中的parameterNameDiscoverer
							//参数名解析器，默认不为null，是DefaultParameterNameDiscoverer实例
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								// 获取指定构造函数的参数名称
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						//通过给定已解析的构造器参数值，创建参数数组以调用构造器或工厂方法，返回参数数组持有者
						//getUserDeclaredConstructor返回原始类的构造器，通常就是本构造器，除非是CGLIB生成的子类
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						//如果参数匹配失败
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						// 保存匹配失败的异常信息，并且下一个构造器的尝试
						if (causes == null) {
							causes = new ArrayDeque<>(1);
						}
						causes.add(ex);
						continue;
					}
				}
				/*
				 * 如果resolvedValues为null，表示通过外部方法显式指定了参数
				 * 比如getBean方法就能指定参数数组
				 */
				else {
					// Explicit arguments given -> arguments length must match exactly.
					//如果构造器参数数量不等于外部方法显式指定的参数数组长度，那么直接结束本次循环继续下一次循环，尝试匹配下一个构造器
					if (parameterCount != explicitArgs.length) {
						continue;
					}
					//如果找到第一个参数长度相等的构造器，那么直接创建一个ArgumentsHolder
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				/*
				 * 类型差异权重计算
				 *
				 * isLenientConstructorResolution判断当前bean定义是以宽松模式还是严格模式解析构造器，工厂方法使用严格模式，其他默认宽松模式（true）
				 * 如果是宽松模式，则调用argsHolder的getTypeDifferenceWeight方法获取类型差异权重，宽松模式使用具有最接近的类型进行匹配
				 * getTypeDifferenceWeight方法用于获取最终参数类型arguments和原始参数类型rawArguments的差异，但是还是以原始类型优先，
				 * 因为原始参数类型的差异值会减去1024，最终返回它们的最小值
				 *
				 * 如果是严格模式，则调用argsHolder的getAssignabilityWeight方法获取类型差异权重，严格模式下必须所有参数类型的都需要匹配（同类或者子类）
				 * 如果有任意一个arguments（先判断）或者rawArguments的类型不匹配，那么直接返回Integer.MAX_VALUE或者Integer.MAX_VALUE - 512
				 * 如果都匹配，那么返回Integer.MAX_VALUE - 1024
				 *
				 *
				 */
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				// 如果它代表着当前最接近的匹配则选择其作为构造函数 差异值越小，越匹配，每次和分数最小的去比较
				if (typeDiffWeight < minTypeDiffWeight) {
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				// 如果两个构造方法与参数值类型列表之间的差异量一致，那么这两个方法都可以作为
				// 候选项，这个时候就出现歧义了，这里先把有歧义的构造方法放入ambiguousConstructors
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					//把候选构造函数 加入到 模棱两可的构造函数集合中
					ambiguousConstructors.add(candidate);
				}
			}

			// 没有可执行的构造方法，抛出异常
			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			//如果模棱两可的构造函数不为空，且为 严格模式，则抛异常
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}
			// 将解析的构造函数、参数 加入缓存
			if (explicitArgs == null && argsHolderToUse != null) {
				/*
				 * 缓存相关信息，比如：
				 *   1. 已解析出的构造方法对象 resolvedConstructorOrFactoryMethod
				 *   2. 构造方法参数列表是否已解析标志 constructorArgumentsResolved
				 *   3. 参数值列表 resolvedConstructorArguments 或 preparedConstructorArguments
				 *
				 * 这些信息可用在其他地方，用于进行快捷判断
				 */
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		// 最终调用instantiate方法，根据有参构造器和需要注入的依赖参数进行反射实例化对象，并将实例化的对象存入当前bw的缓存中
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	/**
	 * 通过构构造器与参数创建bean实例
	 *
	 * @param beanName         beanName
	 * @param mbd              已合并的RootBeanDefinition
	 * @param constructorToUse 被选中的构造器
	 * @param argsToUse        构造器参数数组
	 * @return bean实例
	 */
	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			//获取bean实例化策略对象strategy，默认使用SimpleInstantiationStrategy实例
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			//委托strategy对象的instantiate方法创建bean实例
			return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (isParamMismatch(uniqueCandidate, candidate)) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 * 获取所有的方法,包括父类的方法
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		return (mbd.isNonPublicAccessAllowed() ?
				ReflectionUtils.getUniqueDeclaredMethods(factoryClass) : factoryClass.getMethods());
	}

	private boolean isStaticCandidate(Method method, Class<?> factoryClass) {
		return (Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() == factoryClass);
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * 使用命名工厂方法实例化bean。如果bean定义参数指定了一个类，而不是“工厂bean”，
	 * 或者使用依赖注入配置的工厂对象本身的实例变量，则该方法可能是静态的
	 * <p> 实现需要迭代RootBeanDefinition中指定名称的静态或实例方法（该方法可能已重载），并尝试与参数匹配。
	 * 我们没有附加到构造函数参数的类型，所以只能尝试错误。explicitArgs数组可能包含通过相应的getBean方法以编程方式传递的参数值。
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		// 构造 BeanWrapperImpl 对象
		BeanWrapperImpl bw = new BeanWrapperImpl();
		// 初始化 BeanWrapperImpl
		// 向 BeanWrapper对象中添加 ConversionService 对象和属性编辑器 PropertyEditor 对象
		this.beanFactory.initBeanWrapper(bw);
		// 工厂实例
		Object factoryBean;
		// 工厂类型
		Class<?> factoryClass;
		// 是否静态
		boolean isStatic;

		//获取 factory-bean 属性的值,如果有值就说明是 实例工厂方式实例对象
		String factoryBeanName = mbd.getFactoryBeanName();
		// 非静态方法
		if (factoryBeanName != null) {
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			// 获取 factoryBean 实例
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				throw new ImplicitlyAppearedSingletonException();
			}
			this.beanFactory.registerDependentBean(factoryBeanName, beanName);
			factoryClass = factoryBean.getClass();
			isStatic = false;
		}
		else {
			// It's a static factory method on the bean class.
			// 静态方法，没有 factoryBean 实例
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			factoryBean = null;
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		// 使用的工厂方法
		Method factoryMethodToUse = null;
		//使用的参数包装器
		ArgumentsHolder argsHolderToUse = null;
		// 使用参数
		Object[] argsToUse = null;

		//如果在调用getBean方法时有传参，那就用传的参作为@Bean注解的方法（工厂方法）的参数， 一般懒加载的bean才会传参，启动过程就实例化的实际上都没有传参
		if (explicitArgs != null) {
			argsToUse = explicitArgs;
		}
		else {
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				//不为空表示已经使用过工厂方法，现在是再次使用工厂方法， 一般原型模式和Scope模式采用的上，直接使用该工厂方法和缓存的参数
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}
		// 调用getBean方法没有传参，同时也是第一次使用工厂方法
		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			factoryClass = ClassUtils.getUserClass(factoryClass);

			List<Method> candidates = null;
			// 如果工厂方法是唯一的，没有重载
			if (mbd.isFactoryMethodUnique) {
				if (factoryMethodToUse == null) {
					// 获取工厂方法
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				// 如果存在，构建集合
				if (factoryMethodToUse != null) {
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			if (candidates == null) {
				candidates = new ArrayList<>();
				// 获取factoryClass以及其父类的的所有方法作为候选方法
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				for (Method candidate : rawCandidates) {
					// 查找到与工厂方法同名的候选方法,即有@Bean的同名方法
					if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
						candidates.add(candidate);
					}
				}
			}
			//当与工厂方法同名的候选方法只有一个，且调用getBean方法时没有传参，且没有缓存过参数，直接通过调用实例化方法执行该候选方法
			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				// 获取方法
				Method uniqueCandidate = candidates.get(0);
				// 如果没有参数
				if (uniqueCandidate.getParameterCount() == 0) {
					// 设置工厂方法
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					synchronized (mbd.constructorArgumentLock) {
						// 设置解析出来的方法
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						//设置解析标识
						mbd.constructorArgumentsResolved = true;
						//方法参数为空
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					//创建实例并设置到持有其里面
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}
			// 如果有多个方法，则进行排序
			if (candidates.size() > 1) {  // explicitly skip immutable singletonList
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}

			// 构造器参数值
			ConstructorArgumentValues resolvedValues = null;
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			// 最小的类型差距
			int minTypeDiffWeight = Integer.MAX_VALUE;
			// 模糊的工厂方法集合
			Set<Method> ambiguousFactoryMethods = null;

			// 最小的参数个数
			int minNrOfArgs;
			if (explicitArgs != null) {
				// 若存在显示参数，就是显示参数个数
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				// 如果存在构造器参数值，就解析出最小参数个数
				if (mbd.hasConstructorArgumentValues()) {
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				else {
					minNrOfArgs = 0;
				}
			}

			Deque<UnsatisfiedDependencyException> causes = null;
			//遍历候选方法，查看可以获取的匹配度
			for (Method candidate : candidates) {
				int parameterCount = candidate.getParameterCount();
				//显示参数存在，如果长度不对，则直接下一个
				if (parameterCount >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					Class<?>[] paramTypes = candidate.getParameterTypes();
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						}
						catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new ArrayDeque<>(1);
							}
							causes.add(ex);
							continue;
						}
					}
					//根据匹配度获取类型的差异值
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					// 选择最小的，说明参数类型相近
					if (typeDiffWeight < minTypeDiffWeight) {
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			if (factoryMethodToUse == null || argsToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				if (explicitArgs != null) {
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				else if (resolvedValues != null) {
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
						(mbd.getFactoryBeanName() != null ?
								"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " +
						(minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " +
						(isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
						factoryClass.getName() + "]: needs to have a non-void return type!");
			}
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found on class [" + factoryClass.getName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			// 获取实例化策略进行实例化
			return this.beanFactory.getInstantiationStrategy().instantiate(
					mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 *  将此 bean 的构造器参数解析为resolvedValues对象。这可能涉及查找、初始化其他bean类实例.
	 * <p>This method is also used for handling invocations of static factory methods.
	 * <p>此方法还用于处理静态工厂方法的调用。</p>
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
											ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		// 1.构建bean定义值解析器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		// 2.minNrOfArgs初始化为indexedArgumentValues和genericArgumentValues的的参数个数总和
		int minNrOfArgs = cargs.getArgumentCount();
		// 3.遍历解析带index的参数值
		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				// index从0开始，不允许小于0
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			// 3.1 如果index大于minNrOfArgs，则修改minNrOfArgs
			if (index > minNrOfArgs) {
				// index是从0开始，并且是有序递增的，所以当有参数的index=5时，代表该方法至少有6个参数
				minNrOfArgs = index + 1;
			}
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			// 3.2 解析参数值
			if (valueHolder.isConverted()) {
				// 3.2.1 如果参数值已经转换过，则直接将index和valueHolder添加到resolvedValues的indexedArgumentValues属性
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			} else {
				// 3.2.2 如果值还未转换过，则先进行转换
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				// 3.2.3 使用转换后的resolvedValue构建新的ValueHolder
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				// 3.2.4 将转换前的valueHolder保存到新的ValueHolder的source属性
				resolvedValueHolder.setSource(valueHolder);
				// 3.2.5 将index和新的ValueHolder添加到resolvedValues的indexedArgumentValues属性
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		// 4.遍历解析通用参数值（不带index）
		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			if (valueHolder.isConverted()) {
				// 4.1 如果参数值已经转换过，则直接将valueHolder添加到resolvedValues的genericArgumentValues属性
				resolvedValues.addGenericArgumentValue(valueHolder);
			} else {
				// 4.2 如果值还未转换过，则先进行转换
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				// 4.3 使用转换后的resolvedValue构建新的ValueHolder
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				// 4.4 将转换前的valueHolder保存到新的ValueHolder的source属性
				resolvedValueHolder.setSource(valueHolder);
				// 4.5 将新的ValueHolder添加到resolvedValues的genericArgumentValues属性
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}
		// 5.返回构造函数参数的个数
		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 * 使用给定已解析的构造器参数值，创建参数数组以调用构造器或工厂方法，返回参数数组持有者
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		// 获取类型转换器
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		// 新建一个ArgumentsHolder来存放匹配到的参数
		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		Set<String> autowiredBeanNames = new LinkedHashSet<>(4);

		// 1.遍历参数类型数组
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			// 拿到当前遍历的参数类型
			Class<?> paramType = paramTypes[paramIndex];
			// 拿到当前遍历的参数名
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			// 2.查找当前遍历的参数，是否在mdb对应的bean的构造函数参数中存在index、类型和名称匹配的
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			if (resolvedValues != null) {
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				// 3.如果我们找不到直接匹配并且不应该自动装配，那么让我们尝试下一个通用的无类型参数值作为降级方法：它可以在类型转换后匹配（例如，String - > int）。
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			if (valueHolder != null) {
				// 4.valueHolder不为空，存在匹配的参数
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				// 将valueHolder添加到usedValueHolders
				usedValueHolders.add(valueHolder);
				// 原始属性值
				Object originalValue = valueHolder.getValue();
				// 转换后的属性值
				Object convertedValue;
				if (valueHolder.isConverted()) {
					// 4.1 如果valueHolder已经转换过
					// 4.1.1 则直接获取转换后的值
					convertedValue = valueHolder.getConvertedValue();
					// 4.1.2 将convertedValue作为args在paramIndex位置的预备参数
					args.preparedArguments[paramIndex] = convertedValue;
				}
				else {
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
										ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
										"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					Object sourceHolder = valueHolder.getSource();
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						args.resolveNecessary = true;
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			else {
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					Object autowiredArgument = resolveAutowiredArgument(
							methodParam, beanName, autowiredBeanNames, converter, fallback);
					args.rawArguments[paramIndex] = autowiredArgument;
					args.arguments[paramIndex] = autowiredArgument;
					args.preparedArguments[paramIndex] = autowiredArgumentMarker;
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}


		// 6.如果依赖了其他的bean，则注册依赖关系
		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName +
						"' via " + (executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Class<?>[] paramTypes = executable.getParameterTypes();

		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
			if (argValue == autowiredArgumentMarker) {
				argValue = resolveAutowiredArgument(methodParam, beanName, null, converter, true);
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			}
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			}
			catch (TypeMismatchException ex) {
				throw new UnsatisfiedDependencyException(
						mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
						"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
						"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
			}
		}
		return resolvedArgs;
	}

	protected Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 *  用于解析自动装配的指定参数的模板方法。
	 */
	@Nullable
	protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {

		Class<?> paramType = param.getParameterType();
		// 1.如果参数类型为InjectionPoin
		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			// 1.1 拿到当前的InjectionPoint（存储了当前正在解析依赖的方法参数信息，DependencyDescriptor）
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			// 1.3 返回当前的InjectionPoint

			return injectionPoint;
		}
		try {
			// 2.解析指定依赖，DependencyDescriptor：将MethodParameter的方法参数索引信息封装成DependencyDescriptor
			return this.beanFactory.resolveDependency(
					new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				}
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			throw ex;
		}
	}


	// AOT-oriented pre-resolution

	public Executable resolveConstructorOrFactoryMethod(String beanName, RootBeanDefinition mbd) {
		Supplier<ResolvableType> beanType = () -> getBeanType(beanName, mbd);
		List<ResolvableType> valueTypes = (mbd.hasConstructorArgumentValues() ?
				determineParameterValueTypes(mbd) : Collections.emptyList());
		Method resolvedFactoryMethod = resolveFactoryMethod(beanName, mbd, valueTypes);
		if (resolvedFactoryMethod != null) {
			return resolvedFactoryMethod;
		}

		Class<?> factoryBeanClass = getFactoryBeanClass(beanName, mbd);
		if (factoryBeanClass != null && !factoryBeanClass.equals(mbd.getResolvableType().toClass())) {
			ResolvableType resolvableType = mbd.getResolvableType();
			boolean isCompatible = ResolvableType.forClass(factoryBeanClass)
					.as(FactoryBean.class).getGeneric(0).isAssignableFrom(resolvableType);
			Assert.state(isCompatible, () -> String.format(
					"Incompatible target type '%s' for factory bean '%s'",
					resolvableType.toClass().getName(), factoryBeanClass.getName()));
			Executable executable = resolveConstructor(beanName, mbd,
					() -> ResolvableType.forClass(factoryBeanClass), valueTypes);
			if (executable != null) {
				return executable;
			}
			throw new IllegalStateException("No suitable FactoryBean constructor found for " +
					mbd + " and argument types " + valueTypes);

		}

		Executable resolvedConstructor = resolveConstructor(beanName, mbd, beanType, valueTypes);
		if (resolvedConstructor != null) {
			return resolvedConstructor;
		}

		throw new IllegalStateException("No constructor or factory method candidate found for " +
				mbd + " and argument types " + valueTypes);
	}

	private List<ResolvableType> determineParameterValueTypes(RootBeanDefinition mbd) {
		List<ResolvableType> parameterTypes = new ArrayList<>();
		for (ValueHolder valueHolder : mbd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
			parameterTypes.add(determineParameterValueType(mbd, valueHolder));
		}
		return parameterTypes;
	}

	private ResolvableType determineParameterValueType(RootBeanDefinition mbd, ValueHolder valueHolder) {
		if (valueHolder.getType() != null) {
			return ResolvableType.forClass(
					ClassUtils.resolveClassName(valueHolder.getType(), this.beanFactory.getBeanClassLoader()));
		}
		Object value = valueHolder.getValue();
		if (value instanceof BeanReference br) {
			if (value instanceof RuntimeBeanReference rbr) {
				if (rbr.getBeanType() != null) {
					return ResolvableType.forClass(rbr.getBeanType());
				}
			}
			return ResolvableType.forClass(this.beanFactory.getType(br.getBeanName(), false));
		}
		if (value instanceof BeanDefinition innerBd) {
			String nameToUse = "(inner bean)";
			ResolvableType type = getBeanType(nameToUse,
					this.beanFactory.getMergedBeanDefinition(nameToUse, innerBd, mbd));
			return (FactoryBean.class.isAssignableFrom(type.toClass()) ?
					type.as(FactoryBean.class).getGeneric(0) : type);
		}
		if (value instanceof Class<?> clazz) {
			return ResolvableType.forClassWithGenerics(Class.class, clazz);
		}
		return ResolvableType.forInstance(value);
	}

	@Nullable
	private Executable resolveConstructor(String beanName, RootBeanDefinition mbd,
			Supplier<ResolvableType> beanType, List<ResolvableType> valueTypes) {

		Class<?> type = ClassUtils.getUserClass(beanType.get().toClass());
		Constructor<?>[] ctors = this.beanFactory.determineConstructorsFromBeanPostProcessors(type, beanName);
		if (ctors == null) {
			if (!mbd.hasConstructorArgumentValues()) {
				ctors = mbd.getPreferredConstructors();
			}
			if (ctors == null) {
				ctors = (mbd.isNonPublicAccessAllowed() ? type.getDeclaredConstructors() : type.getConstructors());
			}
		}
		if (ctors.length == 1) {
			return ctors[0];
		}

		Function<Constructor<?>, List<ResolvableType>> parameterTypesFactory = executable -> {
			List<ResolvableType> types = new ArrayList<>();
			for (int i = 0; i < executable.getParameterCount(); i++) {
				types.add(ResolvableType.forConstructorParameter(executable, i));
			}
			return types;
		};
		List<? extends Executable> matches = Arrays.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.NONE))
				.toList();
		if (matches.size() == 1) {
			return matches.get(0);
		}
		List<? extends Executable> assignableElementFallbackMatches = Arrays
				.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.ASSIGNABLE_ELEMENT))
				.toList();
		if (assignableElementFallbackMatches.size() == 1) {
			return assignableElementFallbackMatches.get(0);
		}
		List<? extends Executable> typeConversionFallbackMatches = Arrays
				.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.TYPE_CONVERSION))
				.toList();
		return (typeConversionFallbackMatches.size() == 1 ? typeConversionFallbackMatches.get(0) : null);
	}

	@Nullable
	private Method resolveFactoryMethod(String beanName, RootBeanDefinition mbd, List<ResolvableType> valueTypes) {
		if (mbd.isFactoryMethodUnique) {
			Method resolvedFactoryMethod = mbd.getResolvedFactoryMethod();
			if (resolvedFactoryMethod != null) {
				return resolvedFactoryMethod;
			}
		}

		String factoryMethodName = mbd.getFactoryMethodName();
		if (factoryMethodName != null) {
			String factoryBeanName = mbd.getFactoryBeanName();
			Class<?> factoryClass;
			boolean isStatic;
			if (factoryBeanName != null) {
				factoryClass = this.beanFactory.getType(factoryBeanName);
				isStatic = false;
			}
			else {
				factoryClass = this.beanFactory.resolveBeanClass(mbd, beanName);
				isStatic = true;
			}

			Assert.state(factoryClass != null, () -> "Failed to determine bean class of " + mbd);
			Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
			List<Method> candidates = new ArrayList<>();
			for (Method candidate : rawCandidates) {
				if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
					candidates.add(candidate);
				}
			}

			Method result = null;
			if (candidates.size() == 1) {
				result = candidates.get(0);
			}
			else if (candidates.size() > 1) {
				Function<Method, List<ResolvableType>> parameterTypesFactory = method -> {
					List<ResolvableType> types = new ArrayList<>();
					for (int i = 0; i < method.getParameterCount(); i++) {
						types.add(ResolvableType.forMethodParameter(method, i));
					}
					return types;
				};
				result = (Method) resolveFactoryMethod(candidates, parameterTypesFactory, valueTypes);
			}

			if (result == null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
						(mbd.getFactoryBeanName() != null ?
								"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "'. ");
			}
			return result;
		}

		return null;
	}

	@Nullable
	private Executable resolveFactoryMethod(List<Method> executables,
			Function<Method, List<ResolvableType>> parameterTypesFactory,
			List<ResolvableType> valueTypes) {

		List<? extends Executable> matches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable), valueTypes, FallbackMode.NONE))
				.toList();
		if (matches.size() == 1) {
			return matches.get(0);
		}
		List<? extends Executable> assignableElementFallbackMatches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.ASSIGNABLE_ELEMENT))
				.toList();
		if (assignableElementFallbackMatches.size() == 1) {
			return assignableElementFallbackMatches.get(0);
		}
		List<? extends Executable> typeConversionFallbackMatches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.TYPE_CONVERSION))
				.toList();
		Assert.state(typeConversionFallbackMatches.size() <= 1,
				() -> "Multiple matches with parameters '" + valueTypes + "': " + typeConversionFallbackMatches);
		return (typeConversionFallbackMatches.size() == 1 ? typeConversionFallbackMatches.get(0) : null);
	}

	private boolean match(
			List<ResolvableType> parameterTypes, List<ResolvableType> valueTypes, FallbackMode fallbackMode) {

		if (parameterTypes.size() != valueTypes.size()) {
			return false;
		}
		for (int i = 0; i < parameterTypes.size(); i++) {
			if (!isMatch(parameterTypes.get(i), valueTypes.get(i), fallbackMode)) {
				return false;
			}
		}
		return true;
	}

	private boolean isMatch(ResolvableType parameterType, ResolvableType valueType, FallbackMode fallbackMode) {
		if (isAssignable(valueType).test(parameterType)) {
			return true;
		}
		return switch (fallbackMode) {
			case ASSIGNABLE_ELEMENT -> isAssignable(valueType).test(extractElementType(parameterType));
			case TYPE_CONVERSION -> typeConversionFallback(valueType).test(parameterType);
			default -> false;
		};
	}

	private Predicate<ResolvableType> isAssignable(ResolvableType valueType) {
		return parameterType -> parameterType.isAssignableFrom(valueType);
	}

	private ResolvableType extractElementType(ResolvableType parameterType) {
		if (parameterType.isArray()) {
			return parameterType.getComponentType();
		}
		if (Collection.class.isAssignableFrom(parameterType.toClass())) {
			return parameterType.as(Collection.class).getGeneric(0);
		}
		return ResolvableType.NONE;
	}

	private Predicate<ResolvableType> typeConversionFallback(ResolvableType valueType) {
		return parameterType -> {
			if (valueOrCollection(valueType, this::isStringForClassFallback).test(parameterType)) {
				return true;
			}
			return valueOrCollection(valueType, this::isSimpleValueType).test(parameterType);
		};
	}

	private Predicate<ResolvableType> valueOrCollection(ResolvableType valueType,
			Function<ResolvableType, Predicate<ResolvableType>> predicateProvider) {

		return parameterType -> {
			if (predicateProvider.apply(valueType).test(parameterType)) {
				return true;
			}
			if (predicateProvider.apply(extractElementType(valueType)).test(extractElementType(parameterType))) {
				return true;
			}
			return (predicateProvider.apply(valueType).test(extractElementType(parameterType)));
		};
	}

	/**
	 * Return a {@link Predicate} for a parameter type that checks if its target
	 * value is a {@link Class} and the value type is a {@link String}. This is
	 * a regular use cases where a {@link Class} is defined in the bean
	 * definition as an FQN.
	 * @param valueType the type of the value
	 * @return a predicate to indicate a fallback match for a String to Class
	 * parameter
	 */
	private Predicate<ResolvableType> isStringForClassFallback(ResolvableType valueType) {
		return parameterType -> (valueType.isAssignableFrom(String.class) &&
				parameterType.isAssignableFrom(Class.class));
	}

	private Predicate<ResolvableType> isSimpleValueType(ResolvableType valueType) {
		return parameterType -> (BeanUtils.isSimpleValueType(parameterType.toClass()) &&
				BeanUtils.isSimpleValueType(valueType.toClass()));
	}

	@Nullable
	private Class<?> getFactoryBeanClass(String beanName, RootBeanDefinition mbd) {
		Class<?> beanClass = this.beanFactory.resolveBeanClass(mbd, beanName);
		return (beanClass != null && FactoryBean.class.isAssignableFrom(beanClass) ? beanClass : null);
	}

	private ResolvableType getBeanType(String beanName, RootBeanDefinition mbd) {
		ResolvableType resolvableType = mbd.getResolvableType();
		if (resolvableType != ResolvableType.NONE) {
			return resolvableType;
		}
		return ResolvableType.forClass(this.beanFactory.resolveBeanClass(mbd, beanName));
	}


	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		/**
		 * 解析的构造器和参数存入缓存
		 *
		 * @param mbd                        bean定义
		 * @param constructorOrFactoryMethod 解析的构造器或者工厂方法
		 */
		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				//将当前mbd的的resolvedConstructorOrFactoryMethod设置为当前已解析的构造器或工厂方法
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				//将当前mbd的的constructorArgumentsResolved设置为true
				mbd.constructorArgumentsResolved = true;
				//preparedConstructorArguments和resolvedConstructorArguments这两个缓存只存在一个
				//如果参数需要解析
				if (this.resolveNecessary) {
					//将当前mbd的的preparedConstructorArguments设置为找到的preparedArguments
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				//如果不需要解析
				else {
					//将当前mbd的的resolvedConstructorArguments设置为找到的arguments
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}


	private enum FallbackMode {

		NONE,

		ASSIGNABLE_ELEMENT,

		TYPE_CONVERSION
	}

}
