/*
 * Copyright 2002-2021 the original author or authors.
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.lang.Nullable;

/**
 * Support base class for singleton registries which need to handle
 * {@link org.springframework.beans.factory.FactoryBean} instances,
 * integrated with {@link DefaultSingletonBeanRegistry}'s singleton management.
 * <p>支持单例注册表的基类，它需要处理FactoryBean实例，与DefaultSingletonBeanRegistry的单例管理集成。</p>
 * <p>Serves as base class for {@link AbstractBeanFactory}.
 * <p>用作AbstractBeanFactory的基类。</p>
 *
 * @author Juergen Hoeller
 * @since 2.5.1
 */
public abstract class FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry {

	/**
	 * Cache of singleton objects created by FactoryBeans: FactoryBean name to object.
	 * <p>由FactoryBeans创建的单例对象的缓存:FactoryBean名称到对象</p>
	 */
	private final Map<String, Object> factoryBeanObjectCache = new ConcurrentHashMap<>(16);


	/**
	 * Determine the type for the given FactoryBean.
	 *
	 * @param factoryBean the FactoryBean instance to check
	 * @return the FactoryBean's object type,
	 * or {@code null} if the type cannot be determined yet
	 */
	@Nullable
	protected Class<?> getTypeForFactoryBean(FactoryBean<?> factoryBean) {
		try {
			return factoryBean.getObjectType();
		} catch (Throwable ex) {
			// Thrown from the FactoryBean's getObjectType implementation.
			logger.info("FactoryBean threw exception from getObjectType, despite the contract saying " +
					"that it should return null if the type of its object cannot be determined yet", ex);
			return null;
		}
	}

	/**
	 * Obtain an object to expose from the given FactoryBean, if available
	 * in cached form. Quick check for minimal synchronization.
	 *
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean,
	 * or {@code null} if not available
	 */
	@Nullable
	protected Object getCachedObjectForFactoryBean(String beanName) {
		return this.factoryBeanObjectCache.get(beanName);
	}

	/**
	 * <p>从BeanFactory对象中获取管理的对象.可根据shouldPostProcess对其对象进行该工厂的后置处理:
	 *  <ol>
	 *   <li>如果factory管理的对象是单例 且 beanName已经在该BeanFactory的单例对象的高速缓存Map集合【DefaultListableBeanFactory.singletonObjects】中:
	 *    <ol>
	 *     <li>获取单例互斥体(一般使用singletonObjects)进行加锁,来保证线程安全:
	 *      <ol>
	 *       <li>获取beanName的Bean对象【变量 object】</li>
	 *       <li>如果object为null:
	 *        <ol>
	 *         <li>获取factory管理的对象实例并赋值给object</li>
	 *         <li>重新从factoryBeanObjectCache中获取beanName对应bean对象【变量 alreadyThere】</li>
	 *         <li>如果bean对象不为null,让object引用alreadyThere</li>
	 *         <li>否则:
	 *          <ol>
	 *           <li>如果要进行后处理【shouldPostProcess】:
	 *            <ol>
	 *             <li>如果beanName当前正在创建（在整个工厂内）,直接返回object</li>
	 *             <li>创建单例之前的回调【{@link #beforeSingletonCreation(String)}】</li>
	 *             <li>对从FactoryBean获得的给定对象进行后处理.将处理后的对象重新赋值给object</li>
	 *             <li>捕捉所有在进行后处理的抛出的异常,抛出Bean创建异常</li>
	 *             <li>【finally】:创建单例后的回调【{@link #afterSingletonCreation(String)}】</li>
	 *            </ol>
	 *           </li>
	 *           <li>beanName已经在该BeanFactory的单例对象的高速缓存Map集合【DefaultListableBeanFactory.singletonObjects】中
	 *           将beanName以及object添加到factoryBeanObjectCache中</li>
	 *          </ol>
	 *         </li>
	 *         <li>返回factory管理的对象实例(该对象已经过工厂的后处理)【object】</li>
	 *        </ol>
	 *       </li>
	 *      </ol>
	 *     </li>
	 *    </ol>
	 *   </li>
	 *   <li>否则:
	 *    <ol>
	 *     <li>获取factory管理的对象实例【变量 object】</li>
	 *     <li>如果要进行后处理【shouldPostProcess】:
	 *      <ol>
	 *       <li>对从FactoryBean获得的给定对象进行后处理并赋值给object</li>
	 *       <li>捕捉所有在进行后处理的抛出的异常，抛出Bean创建异常:FactoryBean的单例对象的后处理失败</li>
	 *      </ol>
	 *     </li>
	 *     <li>返回factory管理的对象实例(该对象已经过工厂的后处理)【object】</li>
	 *    </ol>
	 *   </li>
	 *  </ol>
	 * </p>
	 * Obtain an object to expose from the given FactoryBean.
	 * <p>获取一个对象以从给定的FactoryBean中公开</p>
	 *
	 * @param factory           the FactoryBean instance -- FactoryBean实例
	 * @param beanName          the name of the bean -- bean名
	 * @param shouldPostProcess whether the bean is subject to post-processing -- Bean是否要进行后处理
	 * @return the object obtained from the FactoryBean -- 从FactoryBean获得的对象
	 * @throws BeanCreationException if FactoryBean object creation failed
	 *                               -- 如果FactoryBean对象创建失败
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	protected Object getObjectFromFactoryBean(FactoryBean<?> factory, String beanName, boolean shouldPostProcess) {
		//如果factory管理的对象是单例 且 beanName已经在该BeanFactory的单例对象的高速缓存Map集合【DefaultListableBeanFactory.singletonObjects】中
		if (factory.isSingleton() && containsSingleton(beanName)) {
			//获取单例互斥体(一般使用singletonObjects)进行加锁,来保证线程安全
			synchronized (getSingletonMutex()) {
				//获取beanName的Bean对象
				Object object = this.factoryBeanObjectCache.get(beanName);
				//如果object为null
				if (object == null) {
					//获取factory管理的对象实例并赋值给object
					object = doGetObjectFromFactoryBean(factory, beanName);
					// Only post-process and store if not put there already during getObject() call above
					// (e.g. because of circular reference processing triggered by custom getBean calls)
					// 仅在上面的getObject()调用期间进行后处理和存储(如果尚未放置)
					// (例如,由于自定义getBean调用触发的循环引用处理)
					//重新从factoryBeanObjectCache中获取beanName对应bean对象
					Object alreadyThere = this.factoryBeanObjectCache.get(beanName);
					//如果bean对象不为null
					if (alreadyThere != null) {
						//让object引用alreadyThere
						object = alreadyThere;
					} else {
						//如果要进行后处理
						if (shouldPostProcess) {
							//如果beanName当前正在创建（在整个工厂内）
							if (isSingletonCurrentlyInCreation(beanName)) {
								// Temporarily return non-post-processed object, not storing it yet..
								// 暂时返回未处理的对象,尚未存储
								//直接返回object
								return object;
							}
							//创建单例之前的回调
							beforeSingletonCreation(beanName);
							try {
								//对从FactoryBean获得的给定对象进行后处理.
								object = postProcessObjectFromFactoryBean(object, beanName);
							}
							//捕捉所有在进行后处理的抛出的异常
							catch (Throwable ex) {
								//抛出Bean创建异常:FactoryBean的单例对象的后处理失败
								throw new BeanCreationException(beanName,
										"Post-processing of FactoryBean's singleton object failed", ex);
							} finally {
								//创建单例后的回调
								afterSingletonCreation(beanName);
							}
						}
						//beanName已经在该BeanFactory的单例对象的高速缓存Map集合【DefaultListableBeanFactory.singletonObjects】中
						if (containsSingleton(beanName)) {
							//将beanName以及object添加到factoryBeanObjectCache中
							this.factoryBeanObjectCache.put(beanName, object);
						}
					}
				}
				//返回factory管理的对象实例(该对象已经过工厂的后处理)
				return object;
			}
		} else {
//			//获取factory管理的对象实例
			Object object = doGetObjectFromFactoryBean(factory, beanName);
			//如果要进行后处理
			if (shouldPostProcess) {
				try {
					//对从FactoryBean获得的给定对象进行后处理
					object = postProcessObjectFromFactoryBean(object, beanName);
				}
				//捕捉所有在进行后处理的抛出的异常
				catch (Throwable ex) {
					//抛出Bean创建异常:FactoryBean的单例对象的后处理失败
					throw new BeanCreationException(beanName, "Post-processing of FactoryBean's object failed", ex);
				}
			}
			//返回factory管理的对象实例(该对象已经过工厂的后处理)
			return object;
		}
	}


	/**
	 * Obtain an object to expose from the given FactoryBean.
	 * 从给定的FactoryBean调用getObject方法获取要公开的对象。
	 *
	 * @param factory  the FactoryBean instance
	 * @param beanName the name of the bean
	 * @return the object obtained from the FactoryBean
	 * @throws BeanCreationException if FactoryBean object creation failed
	 * @see org.springframework.beans.factory.FactoryBean#getObject()
	 */
	private Object doGetObjectFromFactoryBean(FactoryBean<?> factory, String beanName) throws BeanCreationException {
		Object object;
		try {
			//直接调用FactoryBean的getObject方法获取bean对象实例
			object = factory.getObject();
		} catch (FactoryBeanNotInitializedException ex) {
			throw new BeanCurrentlyInCreationException(beanName, ex.toString());
		} catch (Throwable ex) {
			throw new BeanCreationException(beanName, "FactoryBean threw exception on object creation", ex);
		}

		// Do not accept a null value for a FactoryBean that's not fully
		// initialized yet: Many FactoryBeans just return null then.
		if (object == null) {
			//如果该factoryBean还在创建过程中，那么抛出异常，不接受尚未完全初始化的 FactoryBean 返回的 null 值
			if (isSingletonCurrentlyInCreation(beanName)) {
				throw new BeanCurrentlyInCreationException(
						beanName, "FactoryBean which is currently in creation returned null from getObject");
			}
			//否则使用一个NullBean实例来表示getObject方法就是返回的null，避免空指针
			object = new NullBean();
		}
		return object;
	}

	/**
	 * Post-process the given object that has been obtained from the FactoryBean.
	 * The resulting object will get exposed for bean references.
	 * <p>The default implementation simply returns the given object as-is.
	 * Subclasses may override this, for example, to apply post-processors.
	 *
	 * @param object   the object obtained from the FactoryBean.
	 * @param beanName the name of the bean
	 * @return the object to expose
	 * @throws org.springframework.beans.BeansException if any post-processing failed
	 */
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) throws BeansException {
		return object;
	}

	/**
	 * Get a FactoryBean for the given bean if possible.
	 *
	 * @param beanName     the name of the bean
	 * @param beanInstance the corresponding bean instance
	 * @return the bean instance as FactoryBean
	 * @throws BeansException if the given bean cannot be exposed as a FactoryBean
	 */
	protected FactoryBean<?> getFactoryBean(String beanName, Object beanInstance) throws BeansException {
		if (!(beanInstance instanceof FactoryBean)) {
			throw new BeanCreationException(beanName,
					"Bean instance of type [" + beanInstance.getClass() + "] is not a FactoryBean");
		}
		return (FactoryBean<?>) beanInstance;
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void removeSingleton(String beanName) {
		synchronized (getSingletonMutex()) {
			super.removeSingleton(beanName);
			this.factoryBeanObjectCache.remove(beanName);
		}
	}

	/**
	 * Overridden to clear the FactoryBean object cache as well.
	 */
	@Override
	protected void clearSingletonCache() {
		synchronized (getSingletonMutex()) {
			super.clearSingletonCache();
			this.factoryBeanObjectCache.clear();
		}
	}

}
