/*
 * Copyright 2002-2020 the original author or authors.
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

import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 * <p>共享bean实例的通用注册表，实现了SingletonBeanRegistry。允许注册单例实例，
 * 该实例应该为注册中心的所有调用者共享，并通过bean名称获得。还支持一次性bean实例
 * 的注册(它可能对应于已注册的单例，也可能不对应于已注册的单例)，在注册表关闭时销毁。
 * 可以注册bean之间的依赖关系，以强制执行适当的关闭顺序。</p>
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 * <p>还支持一次性bean实例的注册(它可能对应于已注册的单例，也可能不对应于已注册的单例)，
 * 在注册表关闭时销毁。可以注册bean之间的依赖关系，以强制执行适当的关闭顺序。</p>
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 * <p>这个类主要充当org.springframework.beans.factory.BeanFactory的基类实现，
 * 分解出单例bean实例的通用管理。请注意org.springframework.bean.factory.config.
 * ConfigurableBeanFactory接口扩展了SingletonBeanRegistry接口。</p>
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 * <p>注意，与AbstractBeanFactory和DefaultListableBeanFactory(继承自它)相比，
 * 这个类既不假设bean定义概念，也不假设bean实例的特定创建过程。也可作为嵌套帮助
 * 器使用，以委托给。</p>
 *
 * @author Juergen Hoeller
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 * @since 2.0
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/**
	 * Maximum number of suppressed exceptions to preserve.
	 * 最大数量异常
	 */
	private static final int SUPPRESSED_EXCEPTIONS_LIMIT = 100;


	/**
	 * Cache of singleton objects: bean name to bean instance.
	 * <p>单例对象的高速缓存:beam名称-bean实例，所有bean对象最终都会放到对象中</p>
	 * 一级缓存：缓存已经经历了完整生命周期的bean对象
	 */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/**
	 * Cache of singleton factories: bean name to ObjectFactory.
	 * <p>单例工厂的缓存：bean名称 - ObjectFactory </p>
	 * 三级缓存：缓存的是 ObjectFactory，表示对象工厂，用来创建某个对象的
	 */
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/**
	 * Cache of early singleton objects: bean name to bean instance.
	 * <p>早期单例对象的高速缓存：bean名称 - bean实例</p>
	 * <p>当从singletonFactories中获取到对应对象后，就会放到这个缓存中</p>
	 * 二级缓存：缓存的是早期的 bean对象。早期指的是 Bean 的生命周期还没走完就把这个 Bean 放入了 earlySingletonObjects
	 */
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/**
	 * Set of registered singletons, containing the bean names in registration order.
	 * <p>已注册的单例集，按照注册顺序包含bean名称</p>
	 * <p>用于保证工厂内的beanName是唯一的</p>
	 */
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/**
	 * Names of beans that are currently in creation.
	 * <p>当前正在创建的bean名称</p>
	 * <p>Collections.newSetFromMap(Map):Collections提供了一种保证元素唯一性的Map实现，
	 * 就是用一个Set来表示Map，它持有这个Map的引用，并且保持Map的顺序、并发和性能特征。</p>
	 */
	private final Set<String> singletonsCurrentlyInCreation = Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * Names of beans currently excluded from in creation checks.
	 * <p>当前在创建检查中排除的bean名</p>
	 * <p>Collections.newSetFromMap(Map):Collections提供了一种保证元素唯一性的Map实现，
	 * 就是用一个Set来表示Map，它持有这个Map的引用，并且保持Map的顺序、并发和性能特征。</p>
	 */
	private final Set<String> inCreationCheckExclusions = Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * List of suppressed Exceptions, available for associating related causes.
	 * <p>抑制的异常列表，可用于关联相关原因</p>
	 */
	@Nullable
	private Set<Exception> suppressedExceptions;

	/**
	 * Flag that indicates whether we're currently within destroySingletons.
	 * <p>指示我们当前是否在destroySingletons中的标志</p>
	 */
	private boolean singletonsCurrentlyInDestruction = false;

	/**
	 * Disposable bean instances: bean name to disposable instance.
	 * <p>一次性Bean实例：bean名称 - DisposableBean实例。</p>
	 */
	private final Map<String, Object> disposableBeans = new LinkedHashMap<>();

	/**
	 * Map between containing bean names: bean name to Set of bean names that the bean contains.
	 * <p>在包含的Bean名称之间映射：bean名称 - Bean包含的Bean名称集</p>
	 */
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/**
	 * Map between dependent bean names: bean name to Set of dependent bean names.
	 * <p>存储 bean名到该bean名所要依赖的bean名 的Map，不理解的请看 {@link #registerDependentBean(String, String)}</p>
	 * Map between dependent bean names: bean name to Set of dependent bean names.
	 * <p>在相关的Bean名称之间映射：bean名称 - 一组相关的Bean名称</p>
	 */
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/**
	 * Map between depending bean names: bean name to Set of bean names for the bean's dependencies.
	 * <p>存储 bean名到依赖于该bean名的bean名 的Map，不理解的请看 {@link #registerDependentBean(String, String)}<</p>
	 * Map between depending bean names: bean name to Set of bean names for the bean's dependencies.
	 * <p>在相关的Bean名称之j键映射：bean名称 bean依赖项的Bean名称集</p>
	 */
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	/**
	 * 在给定的bean名称下，在bean注册器中将给定的现有对象注册为单例：
	 * <ol>
	 *     <li>如果beanName,singletonObject为null，抛出异常</li>
	 *     <li>使用singletonObjects作为锁，保证线程安全</li>
	 *     <li>获取beanName在singletonObjects中的单例对象,如果成功获得对象,则抛出异常</li>
	 *     <li>将beanName和singletonObject的映射关系添加到该工厂的单例缓存中</li>
	 * </ol>
	 *
	 * @param beanName        the name of the bean
	 * @param singletonObject the existing singleton object
	 * @throws IllegalStateException
	 */
	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		//如果bean名为null，抛出异常
		Assert.notNull(beanName, "Bean name must not be null");
		//如果单例对象为null，抛出异常
		Assert.notNull(singletonObject, "Singleton object must not be null");
		//使用singletonObjects作为锁，保证线程安全
		synchronized (this.singletonObjects) {
			//获取beanName在singletonObjects中的单例对象
			Object oldObject = this.singletonObjects.get(beanName);
			//如果成功获得对象
			if (oldObject != null) {
				//非法状态异常：不能注册对象[singleton-object]，在bean名'beanName'下，已经有对象[oldObject]
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			//将beanName和singletonObject的映射关系添加到该工厂的单例缓存中
			addSingleton(beanName, singletonObject);
		}
	}


	/**
	 * <p>将beanName和singletonObject的映射关系添加到该工厂的单例缓存中:
	 * 	<ol>
	 * 	    <li>将映射关系添加到singletonObjects【单例对象的高速缓存】中</li>
	 * 	    <li>移除beanName在singletonFactories【单例工厂缓存】中的数据</li>
	 * 	    <li>移除beanName在earlySingletonObjects【早期单例对象的高速缓存】的数据</li>
	 * 	    <li>将beanName添加到registeredSingletons【已注册的单例集】中</li>
	 * 	</ol>
	 * </p>
	 * Add the given singleton object to the singleton cache of this factory.
	 * <p>将给定的单例对象添加到该工厂的单例缓存中。</p>
	 * <p>To be called for eager registration of singletons.
	 * <p>被称为渴望注册的单例</p>
	 *
	 * @param beanName        the name of the bean
	 * @param singletonObject the singleton object
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		synchronized (this.singletonObjects) {
			//将映射关系添加到单例对象的高速缓存中
			this.singletonObjects.put(beanName, singletonObject);
			//移除beanName在单例工厂缓存中的数据
			this.singletonFactories.remove(beanName);
			//移除beanName在早期单例对象的高速缓存的数据
			this.earlySingletonObjects.remove(beanName);
			//将beanName添加到已注册的单例集中
			this.registeredSingletons.add(beanName);
		}
	}


	/**
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>如果需要，添加给定的单例对象工厂来构建指定的单例对象。</p>
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 * <p>指定的单例对象。为单例的快速注册而调用，例如能够解决循环引用。</p>
	 *
	 * @param beanName         the name of the bean -- bean 名
	 * @param singletonFactory the factory for the singleton object -- 单例对象工厂
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		//如果singleFactory为null，抛出异常
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		//使用singletonObjects进行加锁，保证线程安全
		synchronized (this.singletonObjects) {
			//如果 单例对象的高速缓存【beam名称-bean实例】 没有 beanName的对象
			if (!this.singletonObjects.containsKey(beanName)) {
				// 将beanName ，singletonFactory 放到 单例工厂的缓存【bean名称 - ObjectFactory】
				this.singletonFactories.put(beanName, singletonFactory);
				// 从 早期单例对象的高速缓存【bean名称 - bean实例】 移除 beanName的相关缓存对象
				this.earlySingletonObjects.remove(beanName);
				//将beanName 添加 已注册的单例集中
				this.registeredSingletons.add(beanName);
			}
		}
	}


	/**
	 * 获取beanName的单例对象，并允许创建早期引用
	 *
	 * @param beanName the name of the bean to look for - 要寻找的bean名
	 * @see #getSingleton(String, boolean)
	 */
	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		//获取beanName的单例对象，并允许创建早期引用
		return getSingleton(beanName, true);
	}


	/**
	 * <p>获取以beanName注册的(原始)单例对象：
	 *  <ol>
	 *      <li>从单例对象的高速缓存【singletonObjects】中获取beanName的单例对象，赋值为【singletonObject】</li>
	 *      <li>如果单例对象没成功获取，并且 baneName 是正在被创建：
	 *       <ol>
	 *           <li>同步，以singletonObjects作为锁</li>
	 *           <li>从早期单例对象的高速缓存【earlySingletonObjects】中获取bean对象,赋值为【singletonObject】</li>
	 *           <li>如果singletonObject为null，且允许创建早期引用：
	 *            <ol>
	 *               <li>从单例工厂的缓存【singletonFactories】中获取beanName的单例工厂对象，赋值给【singletonFactory】</li>
	 *               <li>如果singletonFactory不为null：
	 *                <ol>
	 *                   <li>从singletonFactory中获取该beanName的单例对象,作为singletonObject</li>
	 *                   <li>添加beanName和singletonObject到 早期单例对象高速缓存【earlySingletonObjects】中</li>
	 *                   <li>从单例对象工厂缓存【singletonFactories】中移除beanName的单例对象工厂</li>
	 *                </ol>
	 *               </li>
	 *            </ol>
	 *           </li>
	 *       </ol>
	 *      </li>
	 *      <li>返回singletonObject</li>
	 *  </ol>
	 * </p>
	 * Return the (raw) singleton object registered under the given name.
	 * <p>返回以给定名称注册的（原始）单例对象，如果单例对象没有找到，并且beanName存在
	 * 正在创建的Set集合中</p>
	 * <p>Checks already instantiated singletons and also allows for an early
	 * reference to a currently created singleton (resolving a circular reference).
	 * <p>检查已经实例化的单例，并且还允许对当前的单例的早期引用（解析循环引用）</p>
	 *
	 * @param beanName            the name of the bean to look for - 要寻找的bean名
	 * @param allowEarlyReference whether early references should be created or not
	 *                            - 是否应创建早期引用
	 * @return the registered singleton object, or {@code null} if none found
	 * - 注册的单例对象；如果找不到，则为{@code null}
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		//从单例对象的高速缓存中获取beanName的单例对象
		Object singletonObject = this.singletonObjects.get(beanName);
		//如果单例对象没有找到，并且 baneName 是正在被创建
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			//同步，以singletonObjects作为锁
			synchronized (this.singletonObjects) {
				//从早期单例对象的高速缓存中获取bean对象
				singletonObject = this.earlySingletonObjects.get(beanName);
				//如果获取不了bean的单例对象，且允许创建早期引用
				if (singletonObject == null && allowEarlyReference) {
					//从单例工厂的缓存中获取beanName的单例工厂对象
					ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
					//如果beanName的单例工厂对象找到了
					if (singletonFactory != null) {
						//从beanName的单例工厂对象中获取该beanName的单例对象
						singletonObject = singletonFactory.getObject();
						//下面的操作主要是为了防止beanName对应的对象重复构建
						//添加beanName和其对应的beanName单例对象到 早期单例对象高速缓存中
						this.earlySingletonObjects.put(beanName, singletonObject);
						//从单例对象工厂缓存中移除beanName的单例对象工厂
						this.singletonFactories.remove(beanName);
					}
				}
			}
		}
		//返回beanName对应的单例对象
		return singletonObject;
	}


	/**
	 * <p>
	 * 返回以beanName的(原始)单例对象，如果尚未注册，则使用singletonFactory创建并注册一个对象:
	 *   <ol>
	 *    <li>如果beanName为null，抛出异常</li>
	 *    <li>使用单例对象的高速缓存Map作为锁，保证线程同步</li>
	 *    <li>从单例对象的高速缓存Map中获取beanName对应的单例对象【变量 singletonObject】,获取成功就直接返回singletonObject</li>
	 *    <li>如果singletonObject获取不到
	 *     <ol>
	 *       <li>如果当前在 destroySingletons中【singletonsCurrentlyInDestruction】，就抛出BeanCreationNotAllowedException</li>
	 *       <li>如果当前日志级别时调试,就打印调试级别日志：创建单例bean的共享实例:'beanName'</li>
	 *       <li>创建单例之前的回调【beforeSingletonCreation(beanName)】,默认实现将单例注册为当前正在创建中</li>
	 *       <li>表示生成了新的单例对象的标记，默认为false，表示没有生成新的单例对象【变量 newSingleton】</li>
	 *       <li>有抑制异常记录标记,没有时为true,否则为false 【变量 recordSuppressedExceptions】</li>
	 *       <li>如果没有抑制异常记录,就对抑制的异常列表【suppressedExceptions】进行实例化(LinkedHashSet)</li>
	 *       <li>从单例工厂中获取对象【变量 singletonObject】</li>
	 *       <li>newSingleton设置为true,表示生成了新的单例对象</li>
	 *       <li>捕捉非法状态异常 【变量 ex】:
	 *        <ol>
	 *          <li>尝试从 单例对象的高速缓存Map 中获取beanName的单例对象。如果获取失败，重新抛出ex。</li>
	 *        </ol>
	 *       </li>
	 *       <li>捕捉Bean创建异常 【变量 ex】
	 *        <ol>
	 *          <li>如果没有抑制异常记录</li>
	 *          <li>遍历抑制的异常列表，元素为suppressedException：将抑制的异常对象添加到 bean创建异常 中，这样其实就相当于
	 *          '因XXX异常导致了Bean创建异常‘ 的说法</li>
	 *          <li>抛出ex</li>
	 *        </ol>
	 *       </li>
	 *       <li>finally:
	 *        <ol>
	 *          <li>如果没有抑制异常记录,将抑制的异常列表置为null。因为suppressedExceptions是对应单个bean的异常记录，
	 *          置为null可防止异常信息的混乱</li>
	 *          <li>创建单例后的回调,默认实现将单例标记为不在创建中 【afterSingletonCreation(beanName)】</li>
	 *        </ol>
	 *       </li>
	 *       <li>如果生成了新的单例对象,将beanName和singletonObject的映射关系添加到该工厂的单例缓存中</li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 * </p>
	 * Return the (raw) singleton object registered under the given name,
	 * creating and registering a new one if none registered yet.
	 * <p>返回以给定名称注册的(原始)单例对象，如果尚未注册，则创建并注册一个
	 * 对象</p>
	 *
	 * @param beanName         the name of the bean -- bean名
	 * @param singletonFactory the ObjectFactory to lazily create the singleton
	 *                         with, if necessary -- 必要时惰性地创建单例的ObjectFactory
	 * @return the registered singleton object -- 注册的单例对象
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		//如果beanName为null，抛出异常
		Assert.notNull(beanName, "Bean name must not be null");
		//使用单例对象的高速缓存Map作为锁，保证线程同步
		synchronized (this.singletonObjects) {
			//从单例对象的高速缓存Map中获取beanName对应的单例对象
			Object singletonObject = this.singletonObjects.get(beanName);
			//如果单例对象获取不到
			if (singletonObject == null) {
				//如果当前在destorySingletons中
				if (this.singletonsCurrentlyInDestruction) {
					//抛出不允许创建Bean异常：在工厂的单例销毁时不允许创建单例bean(请勿在destory方法中向BeanFactory请求Bean)
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
									"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				//如果当前日志级别时调试
				if (logger.isDebugEnabled()) {
					//打印调试级别日志：创建单例bean的共享实例
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				//创建单例之前的回调,默认实现将单例注册为当前正在创建中
				beforeSingletonCreation(beanName);
				//表示生成了新的单例对象的标记，默认为false，表示没有生成新的单例对象
				boolean newSingleton = false;
				//有抑制异常记录标记,没有时为true,否则为false
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				//如果没有抑制异常记录
				if (recordSuppressedExceptions) {
					//对抑制的异常列表进行实例化(LinkedHashSet)
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				try {
					//从单例工厂中获取对象
					singletonObject = singletonFactory.getObject();
					//生成了新的单例对象的标记为true，表示生成了新的单例对象
					newSingleton = true;
				}
				//捕捉非法状态异常
				catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					// 同时，单例对象是否隐式出现 -> 如果是，请继续操作，因为异常表明该状态

					//因为singletonFactory.getObject()的目的就是为将beanName的
					// 单例对象注册到单例对象的高速缓存Map中，忽略掉注册后抛出的非法状态异常，可以保证
					// beanFactory不会因为该bean注册后的后续处理而导致beanFactoury的生命周期结束

					// 默认情况下，sinagletoObjects是拿不到该beanName的，但Spring的作者考虑到自定义BeanFactory的
					// 情况，但不建议在singleFactory#getObject()的方法中就注册到singletonObjects中，因为spring
					// 后面已经帮你将singleObject注册到singleObjects了。

					// 尝试从 单例对象的高速缓存Map 中获取beanName的单例对象
					singletonObject = this.singletonObjects.get(beanName);
					//如果获取失败，抛出异常。
					if (singletonObject == null) {
						throw ex;
					}
				}
				//捕捉Bean创建异常
				catch (BeanCreationException ex) {
					//如果没有抑制异常记录
					if (recordSuppressedExceptions) {
						//遍历抑制的异常列表
						for (Exception suppressedException : this.suppressedExceptions) {
							//将抑制的异常对象添加到 bean创建异常 中，这样做的，就是相当于 '因XXX异常导致了Bean创建异常‘ 的说法
							ex.addRelatedCause(suppressedException);
						}
					}
					//抛出异常
					throw ex;
				} finally {
					//如果没有抑制异常记录
					if (recordSuppressedExceptions) {
						//将抑制的异常列表置为null，因为suppressedExceptions是对应单个bean的异常记录，置为null
						// 可防止异常信息的混乱
						this.suppressedExceptions = null;
					}
					//创建单例后的回调,默认实现将单例标记为不在创建中
					afterSingletonCreation(beanName);
				}
				//生成了新的单例对象
				if (newSingleton) {
					//将beanName和singletonObject的映射关系添加到该工厂的单例缓存中:
					addSingleton(beanName, singletonObject);
				}
			}
			//返回该单例对象
			return singletonObject;
		}
	}

	/**
	 * <p>将要注册的异常对象添加到 抑制异常列表 中，注意抑制异常列表【#suppressedExceptions】是Set集合</p>
	 * Register an Exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * <p>注册一个在创建单例bean实例期间被抑制的异常，例如临时循环引用解析问题。</p>
	 *
	 * @param ex the Exception to register -- 要注册的异常
	 */
	protected void onSuppressedException(Exception ex) {
		//使用singletonObject同步加锁
		synchronized (this.singletonObjects) {
			//如果 抑制异常列表 不为 null
			if (this.suppressedExceptions != null && this.suppressedExceptions.size() < SUPPRESSED_EXCEPTIONS_LIMIT) {
				//将要注册的异常对象添加到 抑制异常列表 中，注意抑制异常列表是Set集合
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * <p>从该工厂单例缓存中删除具有给定名称的Bean。如果创建失败，则能够清理饿汉式注册
	 * 的单例</p>
	 *
	 * @param beanName the name of the bean -- bean名
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		//同步，使用 单例对象的高速缓存:beam名称-bean实例 作为锁
		synchronized (this.singletonObjects) {
			//删除 单例对象的高速缓存:beam名称-bean实例 的对应数据
			this.singletonObjects.remove(beanName);
			//删除 单例工厂的缓存：bean名称 - ObjectFactory 的对应数据
			this.singletonFactories.remove(beanName);
			//删除 单例对象的高速缓存:beam名称-bean实例 的对应数据
			this.earlySingletonObjects.remove(beanName);
			//删除 已注册的单例集，按照注册顺序包含bean名称 的对应数据
			this.registeredSingletons.remove(beanName);
		}
	}

	/**
	 * <p>只是判断一下beanName是否在该BeanFactory的单例对象的高速缓存Map集合【{@link DefaultSingletonBeanRegistry#singletonObjects}】中</p>
	 *
	 * @param beanName the name of the bean to look for -- 要查找的bean名
	 * @return
	 */
	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}


	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		} else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	/**
	 * 给定的bean名是否正在创建
	 * <ol>
	 *   <li>如果beanName没值，抛出异常</li>
	 *   <li>如果 当前在创建检查中排除的bean名列表中不包含该beanName 且 beanName实际上正在创建
	 *   【isActuallyInCreation(beanName)】 就返回true.</li>
	 * </ol>
	 *
	 * @param beanName bean名
	 * @see #isActuallyInCreation(String)
	 */
	public boolean isCurrentlyInCreation(String beanName) {
		//如果beanName没值，抛出异常
		Assert.notNull(beanName, "Bean name must not be null");
		//如果 当前在创建检查中排除的bean名列表中不包含该beanName 且 beanName实际上正在创建 就返回true.
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	/**
	 * 给定的bean名实际上是否正在创建
	 *
	 * @param beanName bean名
	 * @see #isSingletonCurrentlyInCreation(String)
	 */
	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * Return whether the specified singleton bean is currently in creation
	 * (within the entire factory).
	 * <p>返回指定的单例bean当前是否正在创建（在整个工厂内）</p>
	 *
	 * @param beanName the name of the bean - bean名
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		//从 当前正在创建的bean名称 set集合中判断beanName是否在集合中
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * <p>
	 * 创建单例之前的回调:<br/>
	 * 如果 当前在创建检查中的排除bean名列表【inCreationCheckExclusions】中不包含该beanName 且 将beanName添加到
	 * 当前正在创建的bean名称列表【singletonsCurrentlyInCreation】后，出现beanName已经在当前正在创建的bean名称列表中添加过
	 * </p>
	 * Callback before singleton creation.
	 * <p>创建单例之前的回调</p>
	 * <p>The default implementation register the singleton as currently in creation.
	 * <p>默认实现将单例注册为当前正在创建中</p>
	 *
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		//如果 当前在创建检查中的排除bean名列表中不包含该beanName 且 将beanName添加到 当前正在创建的bean名称列表后，出现
		// beanName已经在当前正在创建的bean名称列表中添加过
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			//抛出 当前正在创建的Bean异常
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * <p>
	 * 创建单例后的回调:<br/>
	 * 如果 当前在创建检查中的排除bean名列表中不包含该beanName 且 将beanName从 当前正在创建的bean名称列表 异常后，出现
	 * beanName已经没在当前正在创建的bean名称列表中出现过
	 * </p>
	 * Callback after singleton creation.
	 * <p>创建单例后的回调</p>
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 * <p>默认实现将单例标记为不在创建中</p>
	 *
	 * @param beanName the name of the singleton that has been created -- 已创建的单例的名称
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		//如果 当前在创建检查中的排除bean名列表中不包含该beanName 且 将beanName从 当前正在创建的bean名称列表 异常后，出现
		// beanName已经没在当前正在创建的bean名称列表中出现过
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			//抛出非法状态异常：单例'beanName'不是当前正在创建的
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>将给定Bean添加到注册中心的一次性Bean列表中</p>
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * <p>可处置Bean通常与注册的单例相对应，与Bean名称相匹配，但可能是不同的实例(例如，一个单例的可处置Bean适配器，
	 * 该单例不自然地实现Spring的可处理Bean接口)</p>
	 *
	 * @param beanName the name of the bean -- bean名
	 * @param bean     the bean instance -- bean实例
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		//使用disposableBeans加锁，保证线程安全
		synchronized (this.disposableBeans) {
			//将beanName,bean添加到disposableBeans中
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * <p>将containedBeanName和containingBeanName的包含关系注册到该工厂中
	 *  <ol>
	 *   <li>使用contatinedBeanMap作为锁，保证线程安全:
	 *    <ol>
	 *     <li>从contatinedBeanMap中获取contatingBeanNamed的内部Bean名列表，没有时创建一个初始化长度为
	 *     8的LinkedHashSet来使用【变量 containedBeans】</li>
	 *     <li>将contatedBeanName添加到containedBeans中，如果已经添加过了，就直接返回。</li>
	 *    </ol>
	 *   </li>
	 *   <li>注册containedBeanName与containingBeanName的依赖关系 【{@link #registerDependentBean(String, String)}】</li>
	 *  </ol>
	 * </p>
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>在两个Bean之间注册一个包含关系，例如在内部Bean及其包含的外部Bean之间</p>
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * <p>还要根据破坏顺序将包含的Bean注册为依赖于包含的Bean</p>
	 *
	 * @param containedBeanName  the name of the contained (inner) bean
	 *                           -- 内部Bean名
	 * @param containingBeanName the name of the containing (outer) bean
	 *                           -- 外部Bean名
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		//使用contatinedBeanMap作为锁，保证线程安全
		synchronized (this.containedBeanMap) {
			//从contatinedBeanMap中获取contatingBeanNamed的内部Bean名列表，没有时创建一个初始化长度为8的LinkedHashSet来使用
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			//将contatedBeanName添加到containedBeans中，如果已经添加过了，就直接返回。
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		//注册containedBeanName与containingBeanName的依赖关系
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * <p>注册beanName与dependentBeanNamed的依赖关系：
	 *  <ol>
	 *    <li>获取name的最终别名或者是全类名 【变量 canonicalName】</li>
	 *    <li><b>注册到存储 bean名到该bean名所要依赖的bean名 的Map【dependentBeanMap】：</b>
	 *      <ol>
	 *        <li>使用dependentBeanMap作为锁，保证线程安全</li>
	 *        <li>获取canonicalName对应的用于存储依赖Bean名的Set集合，如果没有就创建一个LinkHashSet，并与canonicalName绑定到dependentBeans中</li>
	 *        <li>如果dependentBeans已经添加过来了dependentBeanName，就结束该方法，不执行后面操作。</li>
	 *      </ol>
	 *    </li>
	 *    <li><b>注册到 存储 bean名到依赖于该bean名的bean名 的Map【dependenciesForBeanMap】：</b>
	 *     <ol>
	 *       <li>使用Bean依赖关系Map作为锁，保证线程安全</li>
	 *       <li>添加dependentBeanName依赖于canonicalName的映射关系到dependenciesForBeanMap中</li>
	 *     </ol>
	 *    </li>
	 *  </ol>
	 * </p>
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * <p>为给定的bean注册一个从属bean，要在给定的bean被销毁之前将其销毁。</p>
	 *
	 * @param beanName          the name of the bean -- bean名
	 * @param dependentBeanName the name of the dependent bean -- 依赖bean名
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		//获取name的最终别名或者是全类名
		String canonicalName = canonicalName(beanName);
		//使用 存储 bean名到该bean名所要依赖的bean名 的Map 作为锁，保证线程安全
		synchronized (this.dependentBeanMap) {
			//获取canonicalName对应的用于存储依赖Bean名的Set集合，如果没有就创建一个LinkeHashSet，并与canonicalName绑定到dependentBeans中
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			//如果dependentBeans已经添加过来了dependentBeanName，就结束该方法，不执行后面操作。
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}

		//使用Bean依赖关系Map作为锁，保证线程安全
		synchronized (this.dependenciesForBeanMap) {
			//添加dependentBeanName依赖于canonicalName的映射关系到 存储 bean名到依赖于该bean名的bean名 的Map中
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * <p>判断beanName是否已注册依赖于dependentBeanName的关系</p>
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * <p>确定指定的依赖bean是否已注册为依赖于给定bean或其任何传递依赖</p>
	 *
	 * @param beanName          the name of the bean to check -- 要检查的bean名
	 * @param dependentBeanName the name of the dependent bean -- 依赖名称
	 * @see #isDependent(String, String, Set)
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		//使用依赖bean关系Map作为锁，保证线程安全
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	/**
	 * <p>确定指定的依赖bean是否已注册为依赖于给定bean或其任何传递依赖：
	 *  <ol>
	 *    <li>如果alreadySeen已经包含该beanName，返回false,表示不依赖</li>
	 *    <li>获取name的最终别名或者是全类名 【变量 canonicalName】</li>
	 *    <li>从 依赖bean关系Map【dependentBeanMap】 中获取canonicalName的依赖bean名【变量 dependentBeans】</li>
	 *    <li>如果没有拿到依赖bean，返回false,表示不依赖</li>
	 *    <li>如果依赖bean名中包含dependendBeanName，返回true，表示是依赖</li>
	 *    <li><b>下面就是为了解决嵌套依赖的情况。如：要检查的是B是否依赖于A，依赖关系是 A->C;C->B，经过下面循环递归
	 *    可得到B依赖于A:</b><br/>循环dependentBeans,元素为dependentBeanName
	 *     <ol>
	 *       <li>如果alreadySeen为null,就实例化一个HashSet</li>
	 *       <li>将beanName添加到alreadySeen</li>
	 *       <li>通过递归该方法的方式检查 dependentBeanName 是否依赖transitiveDependency,是就返回true</li>
	 *     </ol>
	 *    </li>
	 *  </ol>
	 * </p>
	 *
	 * @param beanName          要检查的bean名
	 * @param dependentBeanName 依赖名称
	 * @param alreadySeen       已经检查过的beanName集合,在检查嵌套依赖关系的时候，会对已检查过的beanName直接跳过
	 * @return beanName是否依赖于dependentBeanName
	 */
	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		//如果alreadySeen已经包含该beanName，返回false
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		//获取name的最终别名或者是全类名
		String canonicalName = canonicalName(beanName);
		//从 依赖bean关系Map 中获取canonicalName的依赖bean名
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		//如果没有拿到依赖bean，返回false,表示不依赖
		if (dependentBeans == null) {
			return false;
		}
		//如果依赖bean名中包含dependendBeanName，返回true，表示是依赖
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		//下面就是为了解决嵌套依赖的情况。如：要检查的是B是否依赖于A，依赖关系是 A->C;C->B，经过下面循环递归
		// 可得到B依赖于A。
		//遍历依赖bean名
		for (String transitiveDependency : dependentBeans) {
			//如果alreadySeen为null,就实例化一个HashSet
			if (alreadySeen == null) {
				alreadySeen = new HashSet<>();
			}
			//将beanName添加到alreadySeen
			alreadySeen.add(beanName);
			//通过递归的方式检查 dependentBeanName是否依赖transitiveDependency,是就返回true
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		//返回false，表示不是依赖
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 * <p>确定是否已经为给定名称注册了依赖Bean关系</p>
	 *
	 * @param beanName the name of the bean to check -- 要检查的Bean名
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 * <p>如果有的话，返回依赖于指定Bean的所有Bean名称</p>
	 *
	 * @param beanName the name of the bean
	 *                 -- bean名
	 * @return the array of dependent bean names, or an empty array if none
	 * -- 依赖Bean名称的数组，如果没有，则返回空数组
	 */
	public String[] getDependentBeans(String beanName) {
		//从dependentBeanMap中获取依赖Bean名称的数组
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		//如果 dependentBeans 为null
		if (dependentBeans == null) {
			//返回数组
			return new String[0];
		}
		//使用 dependentBeanMap 进行加锁，以保证Set转数组时的线程安全
		synchronized (this.dependentBeanMap) {
			//将dependentBeans转换为数组
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.
	 * <p>返回指定bean所依赖的所有bean的名称(如果有的话)。</p>
	 *
	 * @param beanName the name of the bean
	 *                 -- bean 名
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 * -- bean所依赖的bean的名称数组，如果没有，则为空数组
	 */
	public String[] getDependenciesForBean(String beanName) {
		//dependenciesForBeanMap：存储 bean名到依赖于该bean名的bean名 的Map
		//从 dependenciesForBeanMap 中获取 beanName 的所依赖的bean的名称数组
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		//如果 dependenciesForBean 为 null
		if (dependenciesForBean == null) {
			//返回空字符串数组
			return new String[0];
		}
		//使用 dependenciesForBeanMap 加锁，保证线程安全
		synchronized (this.dependenciesForBeanMap) {
			// 将 dependenciesForBean 转换为 字符串数组 返回出去
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}


	/**
	 * 销毁当前BeanFactory中的所有单例bean
	 */
	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}

		//同步，使用 单例对象的高速缓存:beam名称-bean实例 作为锁
		synchronized (this.singletonObjects) {
			//将 当前是否在destroySingletons中的标志设置为true，表明正在destroySingletons
			this.singletonsCurrentlyInDestruction = true;
		}

		//
		String[] disposableBeanNames;
		//同步，使用 一次性Bean实例缓存：bean名称 -  DisposableBean实例 作为锁
		synchronized (this.disposableBeans) {
			//复制disposableBean的key集到一个String数组
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		//遍历disposableBeanNames
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			//销毁disposableBeanNames[i])。先销毁依赖于disposableBeanNames[i])的bean,
			// 	然后再销毁bean。
			destroySingleton(disposableBeanNames[i]);
		}

		//清空 在包含的Bean名称之间映射：bean名称 - Bean包含的Bean名称集
		this.containedBeanMap.clear();
		//清空 在相关的Bean名称之间映射：bean名称 - 一组相关的Bean名称
		this.dependentBeanMap.clear();
		//清空 在相关的Bean名称之j键映射：bean名称 bean依赖项的Bean名称集
		this.dependenciesForBeanMap.clear();
		//清除此注册表中所有缓存的单例实例
		clearSingletonCache();
	}


	/**
	 * Clear all cached singleton instances in this registry.
	 * <p>清除此注册表中所有缓存的单例实例</p>
	 *
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		//加锁，使用 单例对象的高速缓存:beam名称-bean实例 作为锁
		synchronized (this.singletonObjects) {
			//清空 单例对象的高速缓存:beam名称-bean实例
			this.singletonObjects.clear();
			//清空 单例工厂的缓存：bean名称 - ObjectFactory
			this.singletonFactories.clear();
			//清空 早期单例对象的高速缓存：bean名称 - bean实例
			this.earlySingletonObjects.clear();
			//清空 已注册的单例集，按照注册顺序包含bean名称
			this.registeredSingletons.clear();
			//设置当前是否在destorySingletons中的标志为false
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * <p>销毁给定的bean。如果找到相应的一次性Bean实例，则委托给{@code destoryBean}</p>
	 *
	 * @param beanName the name of the bean -- bean名
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Remove a registered singleton of the given name, if any.
		// 删除给定名称的已注册的单例（如果有）
		removeSingleton(beanName);

		// Destroy the corresponding DisposableBean instance.
		// 销毁相应的DisposableBean实例
		// DisposableBean:要在销毁时释放资源的bean所实现的接口.包括已注册为一次性的内部bean。
		// 	在工厂关闭时调用。
		DisposableBean disposableBean;
		//同步，将 一次性Bean实例：bean名称 -  DisposableBean实例 作为锁
		synchronized (this.disposableBeans) {
			//从disposableBeans移除出disposableBean对象
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		//销毁给定bean。必须先销毁依赖于给定bean的bean。然后再销毁bean。
		destroyBean(beanName, disposableBean);
	}

	/**
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * <p>销毁给定bean。必须先销毁依赖于给定bean的bean。然后再销毁bean。
	 * 不应抛出任何异常</p>
	 *
	 * @param beanName the name of the bean --  bean名
	 * @param bean     the bean instance to destroy -- 要销毁的bean实例
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// Trigger destruction of dependent beans first...
		// 先触发从依赖的bean的破坏
		Set<String> dependencies;
		//同步,使用 在相关的Bean名称之间映射：bean名称 - 一组相关的Bean名称 作为锁
		synchronized (this.dependentBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			// 在完全同步内以确保断开连续集
			//从dependentBeanMap中移除出beanName对应的依赖beanName集
			dependencies = this.dependentBeanMap.remove(beanName);
		}
		//如果存在依赖的beanName集
		if (dependencies != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			//遍历依赖的BeanName
			for (String dependentBeanName : dependencies) {
				//递归删除dependentBeanName的实例
				destroySingleton(dependentBeanName);
			}
		}

		// Actually destroy the bean now... 实现上现在销毁的bean
		if (bean != null) {
			try {
				//调用销毁方法
				bean.destroy();
			} catch (Throwable ex) {
				//抛出异常时，打印出警告信息
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// Trigger destruction of contained beans... 触发销毁所包含的bean
		Set<String> containedBeans;
		//同步，使用 在包含的Bean名称之间映射：bean名称 - Bean包含的Bean名称集 作为锁
		synchronized (this.containedBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		//如果存在BeanName包含的bean名称集
		if (containedBeans != null) {
			//遍历BeanName包含的bean名称集
			for (String containedBeanName : containedBeans) {
				//递归删除containedBeanName的实例
				destroySingleton(containedBeanName);
			}
		}

		// Remove destroyed bean from other beans' dependencies.
		// 从其他bean的依赖项中删除破坏的bean
		//同步，在相关的Bean名称之间映射：bean名称 - 一组相关的Bean名称 作为锁
		synchronized (this.dependentBeanMap) {
			//遍历dependentBeanMap的元素
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				//从其它bean的依赖bean集合中移除beanName
				dependenciesToClean.remove(beanName);
				//如果依赖bean集合没有任何元素了
				if (dependenciesToClean.isEmpty()) {
					//将整个映射关系都删除
					it.remove();
				}
			}
		}

		// Remove destroyed bean's prepared dependency information.
		// 删除销毁的bean准备的依赖的依赖项信息
		// 从 在相关的Bean名称之键映射：bean名称 bean依赖项的Bean名称集 删除beanName的映射关系
		this.dependenciesForBeanMap.remove(beanName);
	}


	/**
	 * <p>获取单例互斥体，一般使用{@link #singletonObjects}</p>
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>将单例互斥暴露给子类和外部协作者</p>
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 * <p>如果子类执行任何扩展的单例创建阶段,则它们应在给定Object上同步.特别是,子类不应
	 * 在单例创建中涉及其自己的互斥体,以避免在惰性初始化情况下出现死锁的可能性</p>
	 */
	@Override
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
