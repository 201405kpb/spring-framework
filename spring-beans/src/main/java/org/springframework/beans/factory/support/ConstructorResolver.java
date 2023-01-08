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

import org.apache.commons.logging.Log;
import org.springframework.beans.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.core.*;
import org.springframework.lang.Nullable;
import org.springframework.util.*;

import java.beans.ConstructorProperties;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Delegate for resolving constructors and factory methods.
 * <p>代表解析构造函数和工厂方法</p>
 * <p>Performs constructor resolution through argument matching.
 * <p>通过参数匹配执行构造函数解析</p>
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 * @since 2.0
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	/**
	 * Marker for autowired arguments in a cached argument array, to be later replaced
	 * by a {@linkplain #resolveAutowiredArgument resolved autowired argument}.
	 * 缓存的参数数组中自动装配的参数标记，以后将由解析的自动装配参数替换
	 */
	private static final Object autowiredArgumentMarker = new Object();

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 *
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	// BeanWrapper-based construction

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		} else {
			currentInjectionPoint.remove();
		}
		return old;
	}

	/**
	 * <p>以自动注入方式调用最匹配的构造函数来实例化参数对象:
	 *  <ol>
	 *   <li>新建一个BeanWrapperImp实例，用于封装使用工厂方法生成与beanName对应的Bean对象【变量 bw】</li>
	 *   <li>初始化bw</li>
	 *   <li>定义一个用于要适用的构造函数对象的Constructor对象【变量 constructorToUse】</li>
	 *   <li>声明一个用于存储不同形式的参数值的ArgumentsHolder，默认为null【变量 argsHolderToUse】</li>
	 *   <li>定义一个用于要使用的参数值数组 【变量 argsToUse】</li>
	 *   <li>如果explicitArgs不为null,让argsToUse引用explicitArgs</li>
	 *   <li>否则:
	 *    <ol>
	 *     <li>声明一个要解析的参数值数组，默认为null【变量 argsToResolve】</li>
	 *     <li>使用mbd的构造函数字段通用锁进行加锁，以保证线程安全:
	 *      <ol>
	 *       <li>指定constructorToUse引用mbd已解析的构造函数或工厂方法对象</li>
	 *       <li>如果constructorToUse不为null且 mbd已解析构造函数参数:
	 *        <ol>
	 *         <li>指定argsToUse引用mbd完全解析的构造函数参数值</li>
	 *         <li>如果argsToUse为null,指定argsToResolve引用mbd部分准备好的构造函数参数值</li>
	 *        </ol>
	 *       </li>
	 *       <li>如果argsToResolve不为null,即表示mbd还没有完全解析的构造函数参数值,解析缓存在mbd中准备好
	 *       的参数值,允许在没有此类BeanDefintion的时候回退</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>如果constructorToUse为null或者argsToUser为null:
	 *    <ol>
	 *     <li>让candidates引用chosenCtors【变量 candidates】</li>
	 *     <li>如果candidates为null:
	 *      <ol>
	 *       <li>获取mbd的Bean类【变量 beanClass】</li>
	 *       <li>如果mbd允许访问非公共构造函数和方法，获取BeanClass的所有声明构造函数;否则获取
	 *       public的构造函数</li>
	 *       <li>捕捉获取beanClass的构造函数发出的异常,抛出BeanCreationException</li>
	 *      </ol>
	 *     </li>
	 *     <li>如果candidateList只有一个元素 且 没有传入构造函数值 且 mbd也没有构造函数参数值:
	 *      <ol>
	 *       <li>获取candidates中唯一的方法【变量 uniqueCandidate】</li>
	 *       <li>如果uniqueCandidate不需要参数:
	 *        <ol>
	 *         <li>使用mdb的构造函数字段的通用锁【{@link RootBeanDefinition#constructorArgumentLock}】进行加锁以保证线程安全:
	 *          <ol>
	 *           <li>让mbd缓存已解析的构造函数或工厂方法【{@link RootBeanDefinition#resolvedConstructorOrFactoryMethod}】</li>
	 *           <li>让mbd标记构造函数参数已解析【{@link RootBeanDefinition#constructorArgumentsResolved}】</li>
	 *           <li>让mbd缓存完全解析的构造函数参数【{@link RootBeanDefinition#resolvedConstructorArguments}】</li>
	 *          </ol>
	 *         </li>
	 *         <li>使用constructorToUse生成与beanName对应的Bean对象,并将该Bean对象保存到bw中</li>
	 *         <li>将bw返回出去</li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *     <li>定义一个mbd是否支持使用构造函数进行自动注入的标记. 如果chosenCtos不为null或者mbd解析自动注入模式为自动注入可以满足的最
	 *     贪婪的构造函数的常数(涉及解析适当的构造函数)就为true;否则为false 【变量 autowiring】</li>
	 *     <li>定义一个用于存放解析后的构造函数参数值的ConstructorArgumentValues对象【变量 resolvedValues】</li>
	 *     <li>定义一个最少参数数，默认为0【变量 minNrOfArgs】</li>
	 *     <li>如果explicitArgs不为null,minNrOfArgs引用explitArgs的数组长度</li>
	 *     <li>否则:
	 *      <ol>
	 *       <li>获取mbd的构造函数参数值【变量 cargs】</li>
	 *       <li>对resolvedValues实例化</li>
	 *       <li>将cargs解析后值保存到resolveValues中，并让minNrOfArgs引用解析后的最小(索引参数值数+泛型参数值数)</li>
	 *      </ol>
	 *     </li>
	 *     <li>对candidates进行排序</li>
	 *     <li>定义一个最小类型差异权重，默认是Integer最大值【变量 minTypeDiffWeight】</li>
	 *     <li>定义一个存储摸棱两可的工厂方法的Set集合,以用于抛出BeanCreationException时描述异常信息 【变量 ambiguousConstructors】</li>
	 *     <li>定义一个用于UnsatisfiedDependencyException的列表【变量 causes】</li>
	 *     <li>遍历candidates，元素名为candidate:
	 *      <ol>
	 *       <li>获取candidate的参数类型数组【变量 constructorToUse】</li>
	 *       <li>如果constructorToUse不为null且argsToUse不为null且argsToUse的数组长度大于paramTypes的数组长度。即意味着找到最匹配的构造函数：
	 *       跳出遍历循环
	 *       </li>
	 *       <li>如果paramTypes的数组长度小于minNrOfArgs,跳过当次循环中剩下的步骤，执行下一次循环。</li>
	 *       <li>定义一个封装参数数组的ArgumentsHolder对象【变量 argsHolder】</li>
	 *       <li>如果resolveValues不为null：
	 *        <ol>
	 *         <li>获取candidate的ConstructorProperties注解的name属性值作为candidate的参数名【变量 paramNames】</li>
	 *         <li>如果paramName为null
	 *          <ol>
	 *           <li>获取beanFactory的参数名发现器【变量 pnd】</li>
	 *           <li>如果pnd不为null,通过pnd解析candidate的参数名</li>
	 *          </ol>
	 *         </li>
	 *         <li>将resolvedValues转换成一个封装着参数数组ArgumentsHolder实例，当candidate只有一个时，支持可在抛
	 *         出没有此类BeanDefintion的异常返回null，而不抛出异常</li>
	 *         <li>捕捉UnsatisfiedDependencyException:
	 *          <ol>
	 *           <li>如果当前日志可打印跟踪级别的信息,打印跟踪级别日志：忽略bean'beanName'的构造函数[candidate]</li>
	 *           <li>如果cause为null,对cause进行实例化成LinkedList对象</li>
	 *           <li>将ex添加到causes中</li>
	 *           <li>跳过本次循环体中余下尚未执行的语句，立即进行下一次的循环</li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li>否则:
	 *        <ol>
	 *         <li>如果paramTypes数组长度于explicitArgs的数组长度不相等,否则跳过当次循环中剩下的步
	 *         骤，执行下一次循环</li>
	 *         <li>实例化argsHolder，封装explicitArgs到argsHolder</li>
	 *        </ol>
	 *       </li>
	 *       <li>如果bd支持的构造函数解析模式时宽松模式,引用获取类型差异权重值，否则引用获取
	 *       Assignabliity权重值</li>
	 *       <li>如果typeDiffWeight小于minTypeDiffWeight:
	 *        <ol>
	 *         <li>让constructorToUse引用candidate</li>
	 *         <li>让argsHolderToUse引用argsHolder</li>
	 *         <li>让argToUse引用argsHolder的经过转换后参数值数组</li>
	 *         <li>让minTypeDiffWeight引用typeDiffWeight</li>
	 *         <li>将ambiguousFactoryMethods置为null</li>
	 *        </ol>
	 *       </li>
	 *       <li>【else if】如果factoryMethodToUse不为null 且 typeDiffWeight与minTypeDiffWeight相等:
	 *        <ol>
	 *         <li>如果ambiguousFactoryMethods为null:
	 *          <ol>
	 *           <li>初始化ambiguousFactoryMethods为LinkedHashSet实例</li>
	 *           <li>将constructorToUse添加到ambiguousFactoryMethods中</li>
	 *          </ol>
	 *         </li>
	 *         <li>将candidate添加到ambiguousFactoryMethods中</li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *     <li>如果constructorToUse为null:
	 *      <ol>
	 *       <li>如果causes不为null:
	 *        <ol>
	 *         <li>从cause中移除最新的UnsatisfiedDependencyException【变量 ex】</li>
	 *         <li>遍历causes,元素为cause:将cause添加到该Bean工厂的抑制异常列表【{@link DefaultSingletonBeanRegistry#suppressedExceptions}】 中</li>
	 *         <li>重新抛出ex</li>
	 *        </ol>
	 *       </li>
	 *       <li>抛出BeanCreationException：无法解析匹配的构造函数(提示：为简单参数指定索引/类型/名称参数,以避免类型歧义)</li>
	 *      </ol>
	 *     </li>
	 *     <li>【else if】如果ambiguousFactoryMethods不为null 且mbd是使用的是严格模式解析构造函数,抛出BeanCreationException</li>
	 *     <li>如果explicitArgs为null 且 argsHolderToUser不为null,将argsHolderToUse所得到的参数值属性缓存到mbd对应的属性中</li>
	 *    </ol>
	 *   </li>
	 *   <li>如果argsToUse为null，抛出异常：未解析的构造函数参数</li>
	 *   <li>使用factoryBean生成与beanName对应的Bean对象,并将该Bean对象保存到bw中</li>
	 *   <li>将bw返回出去</li>
	 *  </ol>
	 * </p>
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
	 *
	 * @param beanName     the name of the bean -- Bean名
	 * @param mbd          the merged bean definition for the bean -- Bean的BeanDefinition
	 * @param chosenCtors  chosen candidate constructors (or {@code null} if none)  -- 选择的候选构造函数
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 *                     or {@code null} if none (-> use constructor argument values from bean definition)
	 *                     -- 用于构造函数或工厂方法调用的显示参数
	 * @return a BeanWrapper for the new instance -- 新实例的BeanWrapper
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
			} else {
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
					} catch (UnsatisfiedDependencyException ex) {
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
	 * 使用constructorToUse生成与beanName对应的Bean对象:
	 * <ol>
	 *  <li>如果有安全管理器,使用特权方式运行：在beanFactory中返回beanName的Bean实例，并通过
	 *  factoryMethod创建它</li>
	 *  <li>否则：直接在beanFactory中返回beanName的Bean实例，并通过factoryMethod创建它</li>
	 *  <li>捕捉所有实例化对象过程中的异常,抛出BeanCreationException</li>
	 * </ol>
	 * @param beanName  要生成的Bean对象所对应的bean名
	 * @param mbd  beanName对于的合并后RootBeanDefinition
	 * @param constructorToUse 要使用的构造函数
	 * @param argsToUse constructorToUse所用到的参数值
	 * @return 使用constructorToUse生成出来的与beanName对应的Bean对象
	 */
	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			//获取bean实例化策略对象strategy，默认使用SimpleInstantiationStrategy实例
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			//委托strategy对象的instantiate方法创建bean实例
			return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
		} catch (Throwable ex) {
			//抛出BeanCreationException:通过工厂方法实例化Bean失败
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * <p>如果可能，解析指定的beanDefinition中factory方法.:
	 *  <ol>
	 *   <li>定义用于引用工厂类对象的类对象【变量 factoryClass】</li>
	 *   <li>定义是否是静态标记【变量 isStatic】</li>
	 *   <li>如果mbd的FactoryBean名不为null:
	 *    <ol>
	 *     <li>使用beanFactory确定mbd的FactoryBean名的bean类型，将结果赋值给factoryClass。为了
	 *     确定其对象类型，默认让FactoryBean以初始化</li>
	 *     <li>isStatic设置为false，表示不是静态方法</li>
	 *    </ol>
	 *   </li>
	 *   <li>否则：获取mbd包装好的Bean类赋值给factoryClass;将isStatic设置为true，表示是静态方法</li>
	 *   <li>如果factoryClass为null,抛出异常：无法解析工厂类</li>
	 *   <li>如果clazz是CGLIB生成的子类，则返回该子类的父类，否则直接返回要检查的类。将结果重新赋
	 *   值给factoryClass</li>
	 *   <li>根据mbd是否允许访问非公共构造函数和方法来获取factoryClass的所有候选方法【变量 candidates】</li>
	 *   <li>定义用于存储唯一方法对象的Method对象【变量 uniqueCandidate】</li>
	 *   <li>遍历candidates,元素为candidate:
	 *    <ol>
	 *     <li>如果candidate的静态标记与静态标记相同 且 candidate有资格作为工厂方法:
	 *      <ol>
	 *       <li>如果uniqueCandidate还没有引用,将uniqueCandidate引用该candidate</li>
	 *       <li>如果uniqueCandidate的参数类型数组与candidate的参数类型数组不一致,则取消uniqueCandidate的引用,
	 *       并跳出循环</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>将mbd用于自省的唯一工厂方法候选的缓存引用【{@link RootBeanDefinition#factoryMethodToIntrospect}】上uniqueCandidate</li>
	 *  </ol>
	 * </p>
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * <p>如果可能，指定的beanDefinition中解析factory方法.
	 * 可以检查RootBeanDefinition.getResolvedFactoryMethod()的结果</p>
	 * @param mbd the bean definition to check -- 要检查的bean定义
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		//定义用于引用工厂类对象的类对象
		Class<?> factoryClass;
		//定义是否是静态标记
		boolean isStatic;
		//如果mbd的FactoryBean名不为null
		if (mbd.getFactoryBeanName() != null) {
			//使用beanFactory确定mbd的FactoryBean名的bean类型。为了确定其对象类型，默认让FactoryBean以初始化
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			//静态标记设置为false，表示不是静态方法
			isStatic = false;
		}
		else {
			//获取mbd包装好的Bean类
			factoryClass = mbd.getBeanClass();
			//静态标记设置为true，表示是静态方法
			isStatic = true;
		}
		//如果factoryClass为null,抛出异常：无法解析工厂类
		Assert.state(factoryClass != null, "Unresolvable factory class");
		//如果clazz是CGLIB生成的子类，则返回该子类的父类，否则直接返回要检查的类
		factoryClass = ClassUtils.getUserClass(factoryClass);
		//根据mbd的是否允许访问非公共构造函数和方法标记【RootBeanDefinition.isNonPublicAccessAllowed】来获取factoryClass的所有候选方法
		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		//定义用于存储唯一方法对象的Method对象
		Method uniqueCandidate = null;
		//遍历candidates
		for (Method candidate : candidates) {
			//如果candidate的静态标记与静态标记相同 且 candidate有资格作为工厂方法
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				//如果uniqueCandidate还没有引用
				if (uniqueCandidate == null) {
					//将uniqueCandidate引用该candidate
					uniqueCandidate = candidate;
				}
				//如果uniqueCandidate的参数类型数组与candidate的参数类型数组不一致
				else if (isParamMismatch(uniqueCandidate, candidate)) {
					//取消uniqueCandidate的引用
					uniqueCandidate = null;
					//跳出循环
					break;
				}
			}
		}
		//将mbd用于自省的唯一工厂方法候选的缓存引用上uniqueCandidate
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	private boolean isStaticCandidate(Method method, Class<?> factoryClass) {
		return (Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() == factoryClass);
	}

	/**
	 * <p>根据mbd的是否允许访问非公共构造函数和方法标记【{@link RootBeanDefinition#isNonPublicAccessAllowed}】来获取factoryClass的所有候选方法。</p>
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 * <p>考虑RootBeanDefinition.isNoPubilcAccessAllowed()标志,检查给定类的所有候选
	 * 方法.称为确定工厂方法的起点。</p>
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		//如果mbd允许访问非公共构造函数和方法，就返回factoryClass子类和其父类的所有声明方法，首先包括子类方法；
		//	否则只获取factoryClass的public级别方法
		return (mbd.isNonPublicAccessAllowed() ?
				ReflectionUtils.getUniqueDeclaredMethods(factoryClass) : factoryClass.getMethods());
	}

	/**
	 * <p>使用工厂方法实例化beanName所对应的Bean对象:
	 *  <ol>
	 *   <li>新建一个BeanWrapperImp实例，用于封装使用工厂方法生成与beanName对应的Bean对象【变量 bw】</li>
	 *   <li>初始化bw</li>
	 *   <li>【<b>1.获取工厂Bean对象，工厂Bean对象的类对象，确定工厂方法是否是静态</b>】:
	 *    <ol>
	 *     <li>定义一个用于存放工厂Bean对象的Object【变量 factoryBean】</li>
	 *     <li>定义一个用于存放工厂Bean对象的类对象的Class【变量 factoryClass】</li>
	 *     <li>定义一个表示是静态工厂方法的标记【变量 isStatic】</li>
	 *     <li>从mbd中获取配置的FactoryBean名【变量 factoryBeanName】</li>
	 *     <li>如果factoryBeanName不为null：
	 *      <ol>
	 *       <li>如果factoryBean名与beanName相同,抛出BeanDefinitionStoreException</li>
	 *       <li>从bean工厂中获取factoryBeanName所指的factoryBean对象</li>
	 *       <li>如果mbd配置为单例作用域 且 beanName已经在bean工厂的单例对象的高速缓存Map中,抛出 ImplicitlyAppearedSingletonException</li>
	 *       <li>获取factoryBean的Class对象【变量 factoryClass】</li>
	 *       <i>设置isStatic为false,表示不是静态方法</li>
	 *      </ol>
	 *     </li>
	 *     <li>否则：
	 *      <ol>
	 *       <li>如果mbd没有指定bean类,抛出 BeanDefinitionStoreException</li>
	 *       <li>将factoryBean设为null</li>
	 *       <li>指定factoryClass引用mbd指定的bean类</li>
	 *       <li>设置isStatic为true，表示是静态方法</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>2. 尝试从mbd的缓存属性中获取要使用的工厂方法，要使用的参数值数组</b>】:
	 *    <ol>
	 *     <li>声明一个要使用的工厂方法，默认为null【变量 factoryMethodToUse】</li>
	 *     <li>声明一个参数持有人对象，默认为null 【变量 argsHolderToUse】</li>
	 *     <li>声明一个要使用的参数值数组,默认为null 【变量 argsToUse】</li>
	 *     <li>如果explicitArgs不为null,argsToUse就引用explicitArgs</li>
	 *     <li>否则：
	 *      <ol>
	 *       <li>声明一个要解析的参数值数组，默认为null 【变量 argsToResolve】</li>
	 *       <li>使用mbd的构造函数字段通用锁【{@link RootBeanDefinition#constructorArgumentLock}】进行加锁，以保证线程安全:
	 *        <ol>
	 *         <li>指定factoryMethodToUser引用mbd已解析的构造函数或工厂方法对象</li>
	 *         <li>如果factoryMethodToUser不为null 且 mbd已解析构造函数参数:
	 *          <ol>
	 *           <li>指定argsToUser引用mbd完全解析的构造函数参数值</li>
	 *           <li>如果argsToUse为null,指定argsToResolve引用mbd部分准备好的构造函数参数值</li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li>如果argsToResolve不为null,即表示mbd还没有完全解析的构造函数参数值,就解析缓存在mbd中准备好的参数值,
	 *       允许在没有此类BeanDefintion的时候回退</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>3. 在没法从mbd的缓存属性中获取要使用的工厂方法，要使用的参数值数组时，尝试从候选工厂方法中获取要使用的工厂方法以及要使用的参数值数组:</b>】
	 *    <ol>
	 *     <li>如果factoryMethoToUse为null或者argsToUser为null:
	 *      <ol>
	 *       <li><b>3.1 获取工厂类的所有候选工厂方法</b>:
	 *        <ol>
	 *         <li>让factoryClass重新引用经过解决CGLIB问题所得到Class对象</li>
	 *         <li>定义一个用于存储候选方法的集合【变量 candidateList】</li>
	 *         <li>如果mbd所配置工厂方法时唯一:
	 *          <ol>
	 *           <li>如果factoryMethodToUse为null,获取mbd解析后的工厂方法对象</li>
	 *           <li>如果factoryMethodToUse不为null,就新建一个不可变，只能存一个对象的集合，将factoryMethodToUse添加进行，然后
	 *           让candidateList引用该集合</li>
	 *          </ol>
	 *         </li>
	 *         <li>如果candidateList为null:
	 *          <ol>
	 *           <li>让candidateList引用一个新的ArrayList</li>
	 *           <li>根据mbd的是否允许访问非公共构造函数和方法标记【{@link RootBeanDefinition#isNonPublicAccessAllowed}】来获取
	 *           factoryClass的所有候选方法</li>
	 *           <li>遍历rawCandidates,元素名为candidate:如果candidate的修饰符与isStatic一致且candidate有资格作为mdb的
	 *           工厂方法,将candidate添加到candidateList中</li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li>【<b>3.2 候选方法只有一个且没有构造函数时，就直接使用该候选方法生成与beanName对应的Bean对象封装到bw中返回出去</b>】:
	 *        <ol>
	 *         <li>如果candidateList只有一个元素 且 没有传入构造函数值 且 mbd也没有构造函数参数值:
	 *          <ol>
	 *           <li>获取candidateList中唯一的方法 【变量 uniqueCandidate】</li>
	 *           <li>如果uniqueCandidate是不需要参数:
	 *            <ol>
	 *             <li>让mbd缓存uniqueCandidate【{@link RootBeanDefinition#factoryMethodToIntrospect}】</li>
	 *             <li>使用mdb的构造函数字段的通用锁【{@link RootBeanDefinition#constructorArgumentLock}】进行加锁以保证
	 *             线程安全:
	 *              <ol>
	 *               <li>让mbd缓存已解析的构造函数或工厂方法【{@link RootBeanDefinition#resolvedConstructorOrFactoryMethod}】</li>
	 *               <li>让mbd标记构造函数参数已解析【{@link RootBeanDefinition#constructorArgumentsResolved}】</li>
	 *               <li>让mbd缓存完全解析的构造函数参数【{@link RootBeanDefinition#resolvedConstructorArguments}】</li>
	 *              </ol>
	 *             </li>
	 *             <li>使用factoryBean生成的与beanName对应的Bean对象,并将该Bean对象保存到bw中</li>
	 *             <li>将bw返回出去</li>
	 *            </ol>
	 *           </li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li><b>3.3 筛选出最匹配的候选工厂方法，以及解析出去对应的工厂方法的参数值</b>:
	 *        <ol>
	 *         <li>将candidateList转换成数组【变量 candidates】</li>
	 *         <li>对candidates进行排序，首选公共方法和带有最多参数的'greedy'方法</li>
	 *         <li>定义一个用于存放解析后的构造函数参数值的ConstructorArgumentValues对象【变量 resolvedValues】</li>
	 *         <li>定义一个mbd是否支持使用构造函数进行自动注入的标记【变量 autowiring】</li>
	 *         <li>定义一个最小类型差异权重，默认是Integer最大值【变量 minTypeDiffWeight】</li>
	 *         <li>定义一个存储摸棱两可的工厂方法的Set集合,以用于抛出BeanCreationException时描述异常信息【变量 ambiguousFactoryMethods】</li>
	 *         <li>定义一个最少参数数，默认为0【变量 minNrOfArgs】</li>
	 *         <li>如果explicitArgs不为null,minNrOfArgs引用explitArgs的数组长度</li>
	 *         <li>否则:
	 *          <ol>
	 *           <li>如果mbd有构造函数参数值:
	 *            <ol>
	 *             <li>获取mbd的构造函数参数值Holder【变量 cargs】</li>
	 *             <li>对resolvedValues实例化为ConstructorArgumentValues对象</li>
	 *             <li>将cargs解析后值保存到resolveValues中，并让minNrOfArgs引用解析后的最小(索引参数值数+泛型参数值数)</li>
	 *            </ol>
	 *           </li>
	 *           <li>否则：意味着mbd没有构造函数参数值时，将minNrOfArgs设为0</li>
	 *          </ol>
	 *         </li>
	 *         <li>定义一个用于保存UnsatisfiedDependencyException的列表【变量 causes】</li>
	 *         <li>遍历candidate，元素名为candidate：
	 *          <ol>
	 *           <li>获取candidated的参数类型数组【变量 paramTypes】</li>
	 *           <li>如果paramTypes的数组长度大于等于minNrOfArgs:
	 *            <ol>
	 *             <li>定义一个封装参数数组的ArgumentsHolder对象【变量 argsHolder】</li>
	 *             <li>如果explicitArgs不为null:
	 *              <ol>
	 *               <li>如果paramTypes的长度与explicitArgsd额长度不相等,跳过当次循环中剩下的步骤，
	 *               执行下一次循环。</li>
	 *               <li>实例化argsHolder，封装explicitArgs到argsHolder</li>
	 *              </ol>
	 *             </li>
	 *             <li>否则：
	 *              <ol>
	 *               <li>定义用于保存参数名的数组【变量 paramNames】</li>
	 *               <li>获取beanFactory的参数名发现器【变量 pnd】</li>
	 *               <li>如果pnd不为null,通过pnd解析candidate的参数名</li>
	 *               <li>将resolvedValues转换成一个封装着参数数组ArgumentsHolder实例，当candidate只有一个时，支持可在抛
	 *               出没有此类BeanDefintion的异常返回null，而不抛出异常</li>
	 *               <li>捕捉解析参数值时出现的UnsatisfiedDependencyException:
	 *                <ol>
	 *                 <li>如果当前日志可打印跟踪级别的信息,打印跟踪级别日志</li>
	 *                 <li>如果cause为null,对cause进行实例化成LinkedList对象</li>
	 *                 <li>将ex添加到causes中</li>
	 *                 <li>跳过本次循环体中余下尚未执行的语句，立即进行下一次的循环</li>
	 *                </ol>
	 *               </li>
	 *              </ol>
	 *             </li>
	 *             <li>如果bd支持的构造函数解析模式时宽松模式,引用获取类型差异权重值，否则引用获取Assignabliity权重值【变量 typeDiffWeight】</li>
	 *             <li>如果typeDiffWeight小于minTypeDiffWeight:
	 *              <ol>
	 *               <li>让factoryMethodToUser引用candidate</li>
	 *               <li>让argsHolderToUse引用argsHolder</li>
	 *               <li>让argToUse引用argsHolder的经过转换后参数值数组</li>
	 *               <li>让minTypeDiffWeight引用typeDiffWeight</li>
	 *               <li>将ambiguousFactoryMethods置为null</li>
	 *              </ol>
	 *             </li>
	 *             <li>【else if】如果factoryMethodToUse不为null 且 typeDiffWeight与minTypeDiffWeight相等且mbd指定了严格模式解析构造函
	 *             数且paramTypes的数组长度与factoryMethodToUse的参数数组长度相等且paramTypes的数组元素与factoryMethodToUse的参
	 *             数数组元素不相等:
	 *              <ol>
	 *               <li>如果ambiguousFactoryMethods为null:
	 *                <ol>
	 *                 <li>初始化ambiguousFactoryMethods为LinkedHashSet实例</li>
	 *                 <li>将factoryMethodToUse添加到ambiguousFactoryMethods中</li>
	 *                </ol>
	 *               </li>
	 *               <li>将candidate添加到ambiguousFactoryMethods中</li>
	 *              </ol>
	 *             </li>
	 *            </ol>
	 *           </li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *       <li>【<b>3.4 整合无法筛选出候选方法 或者 无法解析出要使用的参数值的情况，抛出BeanCreationException并加以描述</b>】:
	 *        <ol>
	 *         <li>如果factoryMethodToUse为null或者argsToUse为null:
	 *          <ol>
	 *           <li>如果causes不为null,从cause中移除最新的UnsatisfiedDependencyException</li>
	 *           <li>遍历causes,元素为cause:将cause添加到该Bean工厂的抑制异常列表【{@link DefaultSingletonBeanRegistry#suppressedExceptions】中</li>
	 *           <li>定义一个用于存放参数类型的简单类名的ArrayList对象，长度为minNrOfArgs</li>
	 *           <li>如果explicitArgs不为null:遍历explicitArgs.元素为arg:如果arg不为null，将arg的简单类名添加到argTypes中；否则将"null"添加到argTyps中</li>
	 *           <li>如果resolvedValues不为null:
	 *            <ol>
	 *             <li>定义一个用于存放resolvedValues的泛型参数值和方法参数值的LinkedHashSet对象 【变量 valueHolders】</li>
	 *             <li>将resolvedValues的方法参数值添加到valueHolders中</li>
	 *             <li>将resolvedValues的泛型参数值添加到valueHolders中</li>
	 *             <li>遍历valueHolders，元素为value:
	 *              <ol>
	 *               <li>如果value的参数类型不为null，就获取该参数类型的简单类名；否则(如果value的参数值不为null，即获取该参数值的简单类名;否则为"null")</li>
	 *               <li>将argType添加到argTypes中</li>
	 *              </ol>
	 *             </li>
	 *            </ol>
	 *           </li>
	 *           <li>将argType转换成字符串，以","隔开元素.用于描述Bean创建异常</li>
	 *           <li>抛出BeanCreationException</li>
	 *          </ol>
	 *         </li>
	 *         <li>如果factoryMethodToUse时无返回值方法,抛出BeanCreationException</li>
	 *         <li>如果ambiguousFactoryMethods不为null,抛出BeanCreationException</li>
	 *        </ol>
	 *       </li>
	 *       <li>【<b>3.5 将筛选出来的工厂方法和解析出来的参数值缓存到mdb中</b>】:
	 *        <ol>
	 *         <li>如果explicitArgs为null 且 argsHolderToUser不为null:
	 *          <ol>
	 *           <li>让mbd的唯一方法候选【{@link RootBeanDefinition#factoryMethodToIntrospect}】引用factoryMethodToUse</li>
	 *           <li>将argsHolderToUse所得到的参数值属性缓存到mbd对应的属性中</li>
	 *          </ol>
	 *         </li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>【<b>4. 使用factoryBean生成与beanName对应的Bean对象,并将该Bean对象保存到bw中</b>】</li>
	 *   <li>将bw返回出去</li>
	 *  </ol>
	 * </p>
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>使用命名工厂方法实例化bean。如果beanDefinition参数指定一个类，而不是"factory-bean"，或者
	 * 使用依赖注入配置的工厂对象本身的实例，则该方法可以是静态的。</p>
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * <p>实现需要使用RootBeanDefinition中指定的名称迭代静态方法或实例方法(该方法可能会重载),
	 * 并尝试与参数匹配。我们没有将类型附加到构造函数args上,因此反复试验是此处的唯一方法。explicitArgs
	 * 数组可以包含通过相应的getBean方法以编程方式传递的参数值。</p>
	 * @param beanName the name of the bean -- bean名
	 * @param mbd the merged bean definition for the bean -- beanName对于的合并后RootBeanDefinition
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * -- 通过getBean方法以编程方式传递的参数值；如果没有，则返回null(->使用bean定义的构造函数参数值)
	 * @return a BeanWrapper for the new instance -- 新实例的BeanWrapper
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
		} else {
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
		} else {
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
			} else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				// 如果存在构造器参数值，就解析出最小参数个数
				if (mbd.hasConstructorArgumentValues()) {
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				} else {
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
					} else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							String[] paramNames = null;
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								paramNames = pnd.getParameterNames(candidate);
							}
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						} catch (UnsatisfiedDependencyException ex) {
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
				} else if (resolvedValues != null) {
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
			} else if (void.class == factoryMethodToUse.getReturnType()) {
				//抛出BeanCreationException：无法解析匹配的构造函数(提示：为简单参数指定索引/类型/名称参数,以避免类型歧义)
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
								factoryClass.getName() + "]: needs to have a non-void return type!");
			}
			//如果ambiguousFactoryMethods不为null 且mbd是使用的是严格模式解析构造函数
			else if (ambiguousFactoryMethods != null) {
				//抛出BeanCreationException：在bean'beanName'中找到的摸棱两可的构造函数匹配项(提示:为简单参数指定索引/类型/
				// 名称参数以避免类型歧义)：ambiguousFactoryMethods
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found on class [" + factoryClass.getName() + "] " +
								"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
								ambiguousFactoryMethods);
			}

			//如果explicitArgs为null 且 argsHolderToUser不为null
			if (explicitArgs == null && argsHolderToUse != null) {
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				//将argsHolderToUse所得到的参数值属性缓存到mbd对应的属性中
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		//使用factoryBean生成与beanName对应的Bean对象,并将该Bean对象保存到bw中
		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	private Object instantiate(String beanName, RootBeanDefinition mbd,
							   @Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			// 获取实例化策略进行实例化
			return this.beanFactory.getInstantiationStrategy().instantiate(
					mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
		} catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * 将此 bean 的构造器参数解析为resolvedValues对象。这可能涉及查找、初始化其他bean类实例.
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
				} else {
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					} catch (TypeMismatchException ex) {
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
			} else {
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
				} catch (BeansException ex) {
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
	 * <p>解析缓存在给定bean定义中的准备好的参数</p>
	 * @param beanName bean名
	 * @param mbd beanName对于的合并后RootBeanDefinition
	 * @param bw bean的包装类，此时bw还没有拿到bean
	 * @param executable mbd已解析的构造函数或工厂方法对象
	 * @param argsToResolve mbd部分准备好的构造函数参数值
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
											  Executable executable, Object[] argsToResolve) {
		//获取bean工厂的自定义的TypeConverter
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		//如果customConverter不为null,converter就引用customConverter，否则引用bw
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
			} else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
			} else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
			}
			Class<?> paramType = paramTypes[argIndex];
			try {
				resolvedArgs[argIndex] = converter.convertIfNecessary(argValue, paramType, methodParam);
			} catch (TypeMismatchException ex) {
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
			} catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
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

	/**
	 * <p>解析应该自动装配的指定参数的Bean对象:
	 *  <ol>
	 *   <li>获取param的参数类型【变量 paramType】</li>
	 *   <li>如果paramType属于InjectionPoint:
	 *    <ol>
	 *     <li>从线程本地中获取当前切入点对象【变量 injectionPoint】</li>
	 *     <li>如果injectionPoint为null,抛出非法状态异常:当前没有InjectionPoint可用于param</li>
	 *     <li>返回当前injectionPoint对象【injectionPoint】</li>
	 *    </ol>
	 *   </li>
	 *   <li>将param封装成DependencyDescriptor对象，让当前Bean工厂根据该DependencyDescriptor对象的依赖类型解析出与
	 *   该DependencyDescriptor对象所包装的对象匹配的候选Bean对象，然后返回出去:
	 *    <ol>
	 *     <li>捕捉没有唯一的BeanDefinition异常,重写抛出该异常</li>
	 *     <li>捕捉 没有此类beanDefintion异常【变量 ex】:
	 *      <ol>
	 *       <li>如果可在没有此类BeanDefintion的时候回退【fallback】:
	 *        <ol>
	 *         <li>如果paramType是数组类型,根据参数数组的元素类型新建一个空数组对象</li>
	 *         <li>如果paramType是否是常见的Collection类,根据参数类型创建对应一个空的Collection对象</li>
	 *         <li>如果paramType是否是常见的Map类，根据paramType创建对应一个空的Map对象</li>
	 *        </ol>
	 *       </li>
	 *       <li>不可以回退，或者参数类型不是常见数组/集合类型时，重新抛出异常【ex】</li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *  </ol>
	 * </p>
	 * Template method for resolving the specified argument which is supposed to be autowired.
	 * <p>用于解析应该自动装配的指定参数的模板方法</p>
	 * @param param 方法参数封装对象
	 * @param beanName bean名
	 * @param autowiredBeanNames 一个集合，所有自动装配的bean名(用于解决给定依赖关系)都应添加.即自动注入匹配成功的候选Bean名集合。
	 *                              【当autowiredBeanNames不为null，会将所找到的所有候选Bean对象添加到该集合中,以供调用方使用
	 * @param typeConverter 类型装换器
	 * @param fallback 是否可在抛出NoSuchBeanDefinitionException返回null，而不抛出异常
	 */
	@Nullable
	protected Object resolveAutowiredArgument(MethodParameter param, String beanName,
											  @Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {
		//获取param的参数类型
		Class<?> paramType = param.getParameterType();
		//InjectionPoint用于描述一个AOP注入点
		//如果paramType属于InjectionPoint
		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			//从线程本地中获取当前切入点对象，该对象一般在Bean工厂解析出与descriptor所包装的对象匹配的候选Bean对象的时候设置
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			//如果injectionPoint为null
			if (injectionPoint == null) {
				//抛出非法状态异常:当前没有InjectionPoint可用于param
				throw new IllegalStateException("No current InjectionPoint available for " + param);
			}
			//返回当前injectionPoint对象
			return injectionPoint;
		}
		try {
			//DependencyDescriptor：即将注入的特定依赖项描述符。包装构造函数，方法参数或字段，以允许对其元数据 的统一访问
			//该DependencyDescriptor对象的依赖类型就是指param的类型
			//将param封装成DependencyDescriptor对象，让当前Bean工厂根据该DependencyDescriptor对象的依赖类型解析出与
			// 	该DependencyDescriptor对象所包装的对象匹配的候选Bean对象，然后返回出去
			return this.beanFactory.resolveDependency(
					new DependencyDescriptor(param, true), beanName, autowiredBeanNames, typeConverter);
		}
		//捕捉没有唯一的BeanDefinition异常
		catch (NoUniqueBeanDefinitionException ex) {
			//重写抛出该异常
			throw ex;
		}
		//捕捉 没有此类beanDefintion异常
		catch (NoSuchBeanDefinitionException ex) {
			//如果可在没有此类BeanDefintion的时候回退
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				// 单一构造函数或工厂方法->让我们返回一个空数组/集合，例如vararg或非null的List/Set/Map对象
				//如果参数类型是数组类型
				if (paramType.isArray()) {
					//根据参数数组的元素类型新建一个空数组对象
					return Array.newInstance(paramType.getComponentType(), 0);
				}
				//如果paramType是否是常见的Collection类
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					//根据参数类型创建对应的Collection对象
					return CollectionFactory.createCollection(paramType, 0);
				}
				//如果paramType是否是常见的Map类
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					//根据参数类型创建对应的Map对象
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			//不可以回退，或者参数类型不是常见数组/集合类型时，重新抛出异常
			throw ex;
		}
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
			} else {
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
			} else if (candidates.size() > 1) {
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

	/**
	 * Return a {@link Predicate} for a parameter type that checks if its target
	 * value is a {@link Class} and the value type is a {@link String}. This is
	 * a regular use cases where a {@link Class} is defined in the bean
	 * definition as an FQN.
	 *
	 * @param valueType the type of the value
	 * @return a predicate to indicate a fallback match for a String to Class
	 * parameter
	 */
	private Predicate<ResolvableType> isStringForClassFallback(ResolvableType valueType) {
		return parameterType -> (valueType.isAssignableFrom(String.class) &&
				parameterType.isAssignableFrom(Class.class));
	}

	/**
	 * Private inner class for holding argument combinations.
	 * <p>私有内部类，用于保存参数组合</p>
	 */
	private static class ArgumentsHolder {

		/**
		 * 原始参数值数组
		 */
		public final Object[] rawArguments;

		/**
		 * 经过转换后参数值数组
		 */
		public final Object[] arguments;

		/**
		 * 准备好的参数值数组，保存着 由解析的自动装配参数替换的标记和源参数值
		 */
		public final Object[] preparedArguments;

		/**
		 * 需要解析的标记，默认为false
		 */
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

		/**
		 * 获取类型差异权重，宽容模式下使用
		 * <ol>
		 *  <li>获取表示paramTypes和arguments之间的类层次结构差异的权重【变量 typeDiffWeight】</li>
		 *  <li>获取表示paramTypes和rawArguments之间的类层次结构差异的权重【变量 rawTypeDiffWeight】</li>
		 *  <li>比较typeDiffWeight和rawTypeDiffWeight取最小权重并返回出去，但是还是以原始类型优先，因为差异值还-1024</li>
		 * </ol>
		 * @param paramTypes 参数类型数组
		 * @return 类型差异权重最小值
		 */
		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			// 如果找到有效的参数，请确定类型差异权重。尝试对转换后的参数和原始参数都使用类型差异权重。如果
			// 原始重量更好，请使用它。将原始重量减少1024，以使其优于相等的转换重量。
			//MethodInvoker.getTypeDifferenceWeight-确定表示类型和参数之间的类层次结构差异的权重：
			//1. arguments的类型不paramTypes类型的子类，直接返回 Integer.MAX_VALUE,最大重量，也就是直接不匹配
			//2. paramTypes类型是arguments类型的父类则+2
			//3. paramTypes类型是arguments类型的接口，则+1
			//4. arguments的类型直接就是paramTypes类型,则+0
			//获取表示paramTypes和arguments之间的类层次结构差异的权重
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			//获取表示paramTypes和rawArguments之间的类层次结构差异的权重
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			//取最小权重，但是还是以原始类型优先，因为差异值还-1024
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		/**
		 * 获取Assignabliity权重，严格模式下使用
		 * <ol>
		 *  <li>fori形式遍历paramTypes:
		 *   <ol>
		 *    <li>如果确定arguments不是paramTypes的实例,返回Integer最大值;意味着既然连最终的转换后参数值都不能匹配，这个情况下
		 *    paramTypes所对应的工厂方法是不可以接受的</li>
		 *   </ol>
		 *  </li>
		 *  <li>fori形式遍历paramTypes:
		 *   <ol>
		 *    <li>如果确定rawArguments不是paramTypes的实例,返回Integer最大值-512;意味着虽然转换后的参数值匹配，但是原始的参数值不匹配，
		 *    这个情况下的paramTypes所对应的工厂方法还是可以接受的</li>
		 *   </ol>
		 *  </li>
		 *  <li>在完全匹配的情况下，返回Integer最大值-1024；意味着因为最终的转换后参数值和原始参数值都匹配，
		 *  这种情况下paramTypes所对应的工厂方法非常可以接收</li>
		 * </ol>
		 * <p>补充：为啥这里使用Integer.MAX_VALUE作为最初比较值呢？我猜测是因为业务比较是才有谁小谁优先原则。至于为啥-512，和-1024呢？这个我也没懂，但
		 * 至少-512，-1024所得到结果比起-1，-2的结果会明显很多。</p>
		 * @param paramTypes 参数类型
		 * @return Assignabliity权重
		 */
		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			//fori形式遍历paramTypes
			for (int i = 0; i < paramTypes.length; i++) {
				//如果确定arguments不是paramTypes的实例
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					//返回Integer最大值，意味着既然连最终的转换后参数值都不能匹配，这个情况下的paramTypes所对应的工厂方法是不可以接受的
					return Integer.MAX_VALUE;
				}
			}
			//fori形式遍历paramTypes
			for (int i = 0; i < paramTypes.length; i++) {
				//如果确定rawArguments不是paramTypes的实例
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					//返回Integer最大值-512，意味着虽然转换后的参数值匹配，但是原始的参数值不匹配，这个情况下的paramTypes所对应的工厂方法还是可以接受的
					return Integer.MAX_VALUE - 512;
				}
			}
			//在完全匹配的情况下，返回Integer最大值-1024；意味着因为最终的转换后参数值和原始参数值都匹配，这种情况下paramTypes所对应的工厂方法非常可以接收
			return Integer.MAX_VALUE - 1024;
		}

		/**
		 * 将ArgumentsHolder所得到的参数值属性缓存到mbd对应的属性中：
		 * <ol>
		 *  <li>使用mbd的构造函数通用锁【{@link RootBeanDefinition#constructorArgumentLock}】加锁以保证线程安全:
		 *   <ol>
		 *    <li>让mbd的已解析的构造函数或工厂方法【{@link RootBeanDefinition#resolvedConstructorOrFactoryMethod}】引用constructorOrFactoryMethod</li>
		 *    <li>将mdb的构造函数参数已解析标记【{@link RootBeanDefinition#constructorArgumentsResolved}】设置为true</li>
		 *    <li>如果resolveNecessary为true，表示参数还需要进一步解析:
		 *     <ol>
		 *      <li>让mbd的缓存部分准备好的构造函数参数值属性【{@link RootBeanDefinition#preparedConstructorArguments}】引用preparedArguments</li>
		 *      <li>让mbd的缓存完全解析的构造函数参数属性【{@link RootBeanDefinition#resolvedConstructorArguments}】引用arguments</li>
		 *     </ol>
		 *    </li>
		 *   </ol>
		 *  </li>
		 * </ol>
		 * @param mbd bean对象的合并后RootBeanDefinition
		 * @param constructorOrFactoryMethod 匹配的构造函数方法
		 */
		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			//使用mbd的构造函数通用锁【{@link RootBeanDefinition#constructorArgumentLock}】加锁以保证线程安全
			synchronized (mbd.constructorArgumentLock) {
				//让mbd的已解析的构造函数或工厂方法【{@link RootBeanDefinition#resolvedConstructorOrFactoryMethod}】引用constructorOrFactoryMethod
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				//将mdb的构造函数参数已解析标记【{@link RootBeanDefinition#constructorArgumentsResolved}】设置为true
				mbd.constructorArgumentsResolved = true;
				//如果resolveNecessary为true，表示参数还需要进一步解析
				if (this.resolveNecessary) {
					//让mbd的缓存部分准备好的构造函数参数值属性【{@link RootBeanDefinition#preparedConstructorArguments}】引用preparedArguments
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					//让mbd的缓存完全解析的构造函数参数属性【{@link RootBeanDefinition#resolvedConstructorArguments}】引用arguments
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}



	/**
	 * Delegate for checking Java 6's {@link ConstructorProperties} annotation.
	 * <p>用于检查Java 6的{@link ConstructorProperties}注解的委托类</p>
	 * <p>参考博客：https://blog.csdn.net/m0_37668842/article/details/82664680</p>
	 */
	private static class ConstructorPropertiesChecker {

		/**
		 * 获取candidate的ConstructorProperties注解的name属性值
		 * <ol>
		 *  <li>获取candidated中的ConstructorProperties注解 【变量 cp】</li>
		 *  <li>如果cp不为null:
		 *   <ol>
		 *    <li>获取cp指定的getter方法的属性名 【变量 names】</li>
		 *    <li>如果names长度于paramCount不相等,抛出IllegalStateException</li>
		 *    <li>将name返回出去</li>
		 *   </ol>
		 *  </li>
		 *  <li>如果没有配置ConstructorProperties注解，则返回null</li>
		 * </ol>
		 * @param candidate 候选方法
		 * @param paramCount candidate的参数梳理
		 * @return candidate的ConstructorProperties注解的name属性值
		 */
		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			//获取candidated中的ConstructorProperties注解
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			//如果cp不为null
			if (cp != null) {
				//获取cp指定的getter方法的属性名
				String[] names = cp.value();
				//如果names长度于paramCount不相等
				if (names.length != paramCount) {
					//抛出IllegalStateException:用@ConstructorPropertie注解的构造方法，不对应实际的参数数量(paramCount):candidate
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				//将name返回出去
				return names;
			}
			else {
				//如果没有配置ConstructorProperties注解，则返回null
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
