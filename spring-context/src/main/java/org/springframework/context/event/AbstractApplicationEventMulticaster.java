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

package org.springframework.context.event;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Abstract implementation of the {@link ApplicationEventMulticaster} interface,
 * providing the basic listener registration facility.
 * <p>抽线实现了 ApplicationEventMulticaster 接口，提供了基础的监听器注册工具</p>
 * <p>Doesn't permit multiple instances of the same listener by default,
 * as it keeps listeners in a linked Set. The collection class used to hold
 * ApplicationListener objects can be overridden through the "collectionClass"
 * bean property.
 * <p>默认情况下不允许同一个监听器由多个实例，因为它将监听器保持在一个链接的集合张</p>
 *
 * <p>Implementing ApplicationEventMulticaster's actual {@link #multicastEvent} method
 * is left to subclasses. {@link SimpleApplicationEventMulticaster} simply multicasts
 * all events to all registered listeners, invoking them in the calling thread.
 * Alternative implementations could be more sophisticated in those respects.
 * <p>实现 ApplicationEventMulticaster 的实际的 mulicastEvent 方法留给子类。
 * SimpleApplicationEventMulticaster 简单地将所有事件多播给所有注册的监听器，在
 * 调用线程中调用它们。这些方面，可选实现可能更复杂。</p>
 *
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @see #getApplicationListeners(ApplicationEvent, ResolvableType)
 * @see SimpleApplicationEventMulticaster
 * @since 1.2.3
 */
public abstract class AbstractApplicationEventMulticaster
		implements ApplicationEventMulticaster, BeanClassLoaderAware, BeanFactoryAware {

	//创建监听器助手类，用于存放应用程序的监听器集合
	private final DefaultListenerRetriever defaultRetriever = new DefaultListenerRetriever();

	// ListenerCacheKey 是基于事件类型和源类型的类型为key 用来存储监听器助手 CachedListenerRetriever
	final Map<ListenerCacheKey, CachedListenerRetriever> retrieverCache = new ConcurrentHashMap<>(64);

	//类加载器
	@Nullable
	private ClassLoader beanClassLoader;

	//IOC容器工厂类
	@Nullable
	private ConfigurableBeanFactory beanFactory;


	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		//如果 beanFactory 不是 ConfigurableBeanFactory 实例
		if (!(beanFactory instanceof ConfigurableBeanFactory)) {
			// 抛出非法状态异常：不在 ConfigurableBeanFactory：beanFactory
			throw new IllegalStateException("Not running in a ConfigurableBeanFactory: " + beanFactory);
		}
		this.beanFactory = (ConfigurableBeanFactory) beanFactory;
		//如果 beanClassLoader 为 null
		if (this.beanClassLoader == null) {
			//获取beanFactory的类加载器以加载Bean类(即使无法使用系统ClassLoader,也只能为null)
			this.beanClassLoader = this.beanFactory.getBeanClassLoader();
		}
	}

	//获取当前BeanFactory
	private ConfigurableBeanFactory getBeanFactory() {
		//如果 beanFactory 为 null
		if (this.beanFactory == null) {
			//抛出非法状态异常：ApplicationEventMulticaster不能检索监听器Bean，因为它与BeanFactory没有关联
			throw new IllegalStateException("ApplicationEventMulticaster cannot retrieve listener beans " +
					"because it is not associated with a BeanFactory");
		}
		//返回当前BeanFactory
		return this.beanFactory;
	}


	/**
	 * 添加应用程序监听类
	 *
	 * @param listener the listener to add
	 */
	@Override
	public void addApplicationListener(ApplicationListener<?> listener) {
		// 锁定监听器助手类
		synchronized (this.defaultRetriever) {
			// Explicitly remove target for a proxy, if registered already,
			// in order to avoid double invocations of the same listener.
			// 如果注册，则删除已经注册的监听器对象，为了避免调用重复的监听器对象
			Object singletonTarget = AopProxyUtils.getSingletonTarget(listener);
			if (singletonTarget instanceof ApplicationListener) {
				//删除监听器对象
				this.defaultRetriever.applicationListeners.remove(singletonTarget);
			}
			//新增监听器对象
			this.defaultRetriever.applicationListeners.add(listener);
			// 清空监听器助手缓存map
			this.retrieverCache.clear();
		}
	}

	/**
	 * 添加自定义监听器beanName
	 */
	@Override
	public void addApplicationListenerBean(String listenerBeanName) {
		//加锁
		synchronized (this.defaultRetriever) {
			//加入到默认检索器的applicationListenerBeans集合
			this.defaultRetriever.applicationListenerBeans.add(listenerBeanName);
			//清除缓存
			this.retrieverCache.clear();
		}
	}

	@Override
	public void removeApplicationListener(ApplicationListener<?> listener) {
		//使用 retrievalMutex 加锁，保证线程安全
		synchronized (this.defaultRetriever) {
			//将 listener 从 retriever的ApplicationListener 对象集合中 移除
			this.defaultRetriever.applicationListeners.remove(listener);
			//清空缓存，因为 listener 可能支持缓存的某些事件类型和源类型 ，所以要刷新缓存
			this.retrieverCache.clear();
		}
	}

	@Override
	public void removeApplicationListenerBean(String listenerBeanName) {
		//使用 defaultRetriever 加锁，保证线程安全
		synchronized (this.defaultRetriever) {
			//将 listener 从 retriever的ApplicationListener 对象集合中 移除
			this.defaultRetriever.applicationListenerBeans.remove(listenerBeanName);
			//清空缓存，因为 listener 可能支持缓存的某些事件类型和源类型 ，所以要刷新缓存
			this.retrieverCache.clear();
		}
	}

	@Override
	public void removeApplicationListeners(Predicate<ApplicationListener<?>> predicate) {
		//使用 defaultRetriever 加锁，保证线程安全
		synchronized (this.defaultRetriever) {
			this.defaultRetriever.applicationListeners.removeIf(predicate);
			this.retrieverCache.clear();
		}
	}

	@Override
	public void removeApplicationListenerBeans(Predicate<String> predicate) {
		synchronized (this.defaultRetriever) {
			this.defaultRetriever.applicationListenerBeans.removeIf(predicate);
			this.retrieverCache.clear();
		}
	}

	@Override
	public void removeAllListeners() {
		//使用 defaultRetriever 加锁，保证线程安全
		synchronized (this.defaultRetriever) {
			//清空 defaultRetriever 的ApplicationListener 对象集合
			this.defaultRetriever.applicationListeners.clear();
			//清空 defaultRetriever 的 BeanFactory中的 applicationListener 类型 Bean名 集合
			this.defaultRetriever.applicationListenerBeans.clear();
			//清空缓存，因为 listener 可能支持缓存的某些事件类型和源类型 ，所以要刷新缓存
			this.retrieverCache.clear();
		}
	}


	/**
	 * Return a Collection containing all ApplicationListeners.
	 * <p>返回包含所有 applicationListener 的集合</p>
	 *
	 * @return a Collection of ApplicationListeners
	 * -- applicationListener集合
	 * @see org.springframework.context.ApplicationListener
	 */
	protected Collection<ApplicationListener<?>> getApplicationListeners() {
		synchronized (this.defaultRetriever) {
			return this.defaultRetriever.getApplicationListeners();
		}
	}

	/**
	 * Return a Collection of ApplicationListeners matching the given
	 * event type. Non-matching listeners get excluded early.
	 * 返回与给定事件类型匹配的应用程序监听器集合，不匹配的监听器会提前被排除。
	 *
	 * @param event     the event to be propagated. Allows for excluding
	 *                  non-matching listeners early, based on cached matching information.
	 *                  要传播的事件。允许根据缓存的匹配信息尽早排除不匹配的侦听器。
	 * @param eventType the event type 事件类型
	 * @return a Collection of ApplicationListeners 监听器列表
	 * @see org.springframework.context.ApplicationListener
	 */
	protected Collection<ApplicationListener<?>> getApplicationListeners(
			ApplicationEvent event, ResolvableType eventType) {
		//获取需要传递的源数据
		Object source = event.getSource();
		//获取源类型
		Class<?> sourceType = (source != null ? source.getClass() : null);
		//根据事件类型和源类型创建一个缓存key对象
		ListenerCacheKey cacheKey = new ListenerCacheKey(eventType, sourceType);

		// Potential new retriever to populate
		CachedListenerRetriever newRetriever = null;

		// Quick check for existing entry on ConcurrentHashMap
		//快速检查ConcurrentHashMap上的现有的缓存...
		CachedListenerRetriever existingRetriever = this.retrieverCache.get(cacheKey);
		if (existingRetriever == null) {
			// Caching a new ListenerRetriever if possible
			if (this.beanClassLoader == null ||
					(ClassUtils.isCacheSafe(event.getClass(), this.beanClassLoader) &&
							(sourceType == null || ClassUtils.isCacheSafe(sourceType, this.beanClassLoader)))) {
				newRetriever = new CachedListenerRetriever();
				existingRetriever = this.retrieverCache.putIfAbsent(cacheKey, newRetriever);
				if (existingRetriever != null) {
					newRetriever = null;  // no need to populate it in retrieveApplicationListeners
				}
			}
		}

		//如果检索器不为null，那么返回检索器中缓存的监听器，这也是为什么在此前添加监听器之后，这个缓存会被清空的原因
		if (existingRetriever != null) {
			Collection<ApplicationListener<?>> result = existingRetriever.getApplicationListeners();
			if (result != null) {
				return result;
			}
			// If result is null, the existing retriever is not fully populated yet by another thread.
			// Proceed like caching wasn't possible for this current local attempt.
		}

		// 直接检索给定事件和源类型对应的监听器，也不需要缓存
		return retrieveApplicationListeners(eventType, sourceType, newRetriever);
	}


	/**
	 * Actually retrieve the application listeners for the given event and source type.
	 * 实际上检索给定事件和源类型的应用程序监听器。
	 *
	 * @param eventType  the event type 事件类型
	 * @param sourceType the event source type 事件源类型
	 * @param retriever  the ListenerRetriever, if supposed to populate one (for caching purposes)
	 *                   监听器检索器，用于缓存找到的监听器，可以为null
	 * @return the pre-filtered list of application listeners for the given event and source type
	 * 适用于给定事件和源类型的应用程序监听器列表
	 */
	private Collection<ApplicationListener<?>> retrieveApplicationListeners(
			ResolvableType eventType, @Nullable Class<?> sourceType, @Nullable CachedListenerRetriever retriever) {
		//所有的监听器
		List<ApplicationListener<?>> allListeners = new ArrayList<>();
		Set<ApplicationListener<?>> filteredListeners = (retriever != null ? new LinkedHashSet<>() : null);
		Set<String> filteredListenerBeans = (retriever != null ? new LinkedHashSet<>() : null);

		//Spring管理的监听器Bean
		Set<ApplicationListener<?>> listeners;
		//Spring管理的监听器Bean
		Set<String> listenerBeans;
		//初始化，集合，直接从defaultRetriever获取缓存的监听器
		synchronized (this.defaultRetriever) {
			//这里面可能包括不匹配的监听器
			listeners = new LinkedHashSet<>(this.defaultRetriever.applicationListeners);
			listenerBeans = new LinkedHashSet<>(this.defaultRetriever.applicationListenerBeans);
		}

		// Add programmatically registered listeners, including ones coming
		// from ApplicationListenerDetector (singleton beans and inner beans).
		//添加以编程方式注册的监听器
		for (ApplicationListener<?> listener : listeners) {
			/*
			 * 是否支持该事件类型和事件源类型
			 *
			 * 如果是普通ApplicationListener监听器：
			 *  则判断监听器的泛型事件类型是否与给定事件的类型匹配或者兼容
			 * 如果是@EventListener监听器：
			 *  则是判断方法参数以及注解中的value、classes属性指定的类型是否与给定事件的类型匹配或者兼容
			 *  或者，如果该事件为PayloadApplicationEvent类型，则判断与该事件的有效载荷的类型是否匹配或者兼容
			 *
			 *  也就是说@EventListener方法参数以及注解value、classes属性的类型可以直接是事件的载荷类型
			 *  也就是只要与发布的事件类型一致，就能监听到，不一定非得是一个真正的ApplicationEvent事件类型
			 */
			if (supportsEvent(listener, eventType, sourceType)) {
				//如果检索器不为null，那么存入该检索器，用于缓存
				if (retriever != null) {
					filteredListeners.add(listener);
				}
				//把支持该事件的监听器加入到allListeners集合
				allListeners.add(listener);
			}
		}

		// Add listeners by bean name, potentially overlapping with programmatically
		// registered listeners above - but here potentially with additional metadata.
		// 尝试添加通过Spring注册的监听器，虽然在此前的ApplicationListenerDetector中已经注册了一部分
		// 但是仍然可能存在@Lazy的监听器或者prototype的监听器，那么在这里初始化
		if (!listenerBeans.isEmpty()) {
			ConfigurableBeanFactory beanFactory = getBeanFactory();
			//遍历beanName
			for (String listenerBeanName : listenerBeans) {
				try {
					/*是否支持该事件类型*/
					if (supportsEvent(beanFactory, listenerBeanName, eventType)) {
						//如果支持，那么这里通过Spring初始化当前监听器实例
						ApplicationListener<?> listener =
								beanFactory.getBean(listenerBeanName, ApplicationListener.class);
						//如果已获取的集合中不包含该监听器，并且当前监听器实例支持支持该事件类型和事件源类型
						if (!allListeners.contains(listener) && supportsEvent(listener, eventType, sourceType)) {
							//如果检索器不为null，那么存入该检索器，用于缓存
							if (retriever != null) {
								//如果是singleton的监听器，那么有可能会是@Lazy导致懒加载的，那么直接将实例加入到applicationListeners集合
								if (beanFactory.isSingleton(listenerBeanName)) {
									filteredListeners.add(listener);
								} else {
									//如果是其他作用域的监听器，比如prototype，这表示在每次触发时需要创建新的监听器实例
									//那么不能缓存该监听器实例，而是将监听器的beanName加入到applicationListenerBeans集合
									filteredListenerBeans.add(listenerBeanName);
								}
							}
							//把支持该事件的监听器加入到allListeners集合
							allListeners.add(listener);
						}
					} else {
						// Remove non-matching listeners that originally came from
						// ApplicationListenerDetector, possibly ruled out by additional
						// BeanDefinition metadata (e.g. factory method generics) above.
						// 将不匹配该事件的singleton的监听器实例移除
						Object listener = beanFactory.getSingleton(listenerBeanName);
						if (retriever != null) {
							filteredListeners.remove(listener);
						}
						//同样从要返回的集合中移除
						allListeners.remove(listener);
					}
				} catch (NoSuchBeanDefinitionException ex) {
					// Singleton listener instance (without backing bean definition) disappeared -
					// probably in the middle of the destruction phase
				}
			}
		}

		//最后使用AnnotationAwareOrderComparator比较器对监听器进行排序，这说明监听器支持order排序
		//该比较器支持Ordered、PriorityOrdered接口，以及@Order、@Priority注解的排序，比较优先级为PriorityOrdered>Ordered>@Ordered>@Priority，
		//排序规则是order值越小排序越靠前，优先级越高，没有order值则默认排在尾部，优先级最低。
		AnnotationAwareOrderComparator.sort(allListeners);
		//如果最终没有适用于给定事件的SpringBean
		if (retriever != null) {
			if (filteredListenerBeans.isEmpty()) {
				retriever.applicationListeners = new LinkedHashSet<>(allListeners);
				retriever.applicationListenerBeans = filteredListenerBeans;
			} else {
				retriever.applicationListeners = filteredListeners;
				retriever.applicationListenerBeans = filteredListenerBeans;
			}
		}
		//返回
		return allListeners;
	}

	/**
	 * <p>通过在尝试实例化listenerBeanName的BeanDefinition的监听器之前检查其泛型声明的事件类型是否支持 eventType </p>
	 * Filter a bean-defined listener early through checking its generically declared
	 * event type before trying to instantiate it.
	 * <p>通过在尝试实例化BeanDefinition的监听器之前检查其通用声明的事件类型，尽早筛选监听器</p>
	 * <p>If this method returns {@code true} for a given listener as a first pass,
	 * the listener instance will get retrieved and fully evaluated through a
	 * {@link #supportsEvent(ApplicationListener, ResolvableType, Class)} call afterwards.
	 * <p>如果该方法子啊第一次传递时对给定的监听器返回true，则随后将通过
	 * supportsEvent(ApplicationListener, ResolvableType, Class) 调用检索并全面评估监听器实例</p>
	 *
	 * @param beanFactory      the BeanFactory that contains the listener beans
	 *                         -- 包含监听器Bean的BeanFactory
	 * @param listenerBeanName the name of the bean in the BeanFactory
	 *                         -- 在 BeanFactory 中的Bean名
	 * @param eventType        the event type to check
	 *                         -- 要检查的事件类型
	 * @return whether the given listener should be included in the candidates
	 * for the given event type
	 * -- 给定的监听器是否应包含在给定事件类型的候选中
	 * @see #supportsEvent(Class, ResolvableType)
	 * @see #supportsEvent(ApplicationListener, ResolvableType, Class)
	 */
	private boolean supportsEvent(
			ConfigurableBeanFactory beanFactory, String listenerBeanName, ResolvableType eventType) {

		Class<?> listenerType = beanFactory.getType(listenerBeanName);
		if (listenerType == null || GenericApplicationListener.class.isAssignableFrom(listenerType) ||
				SmartApplicationListener.class.isAssignableFrom(listenerType)) {
			return true;
		}
		if (!supportsEvent(listenerType, eventType)) {
			return false;
		}
		try {
			BeanDefinition bd = beanFactory.getMergedBeanDefinition(listenerBeanName);
			ResolvableType genericEventType = bd.getResolvableType().as(ApplicationListener.class).getGeneric();
			return (genericEventType == ResolvableType.NONE || genericEventType.isAssignableFrom(eventType));
		} catch (NoSuchBeanDefinitionException ex) {
			// Ignore - no need to check resolvable type for manually registered singleton
			return true;
		}
	}

	/**
	 * Filter a listener early through checking its generically declared event
	 * type before trying to instantiate it.
	 * <p>If this method returns {@code true} for a given listener as a first pass,
	 * the listener instance will get retrieved and fully evaluated through a
	 * {@link #supportsEvent(ApplicationListener, ResolvableType, Class)} call afterwards.
	 *
	 * @param listenerType the listener's type as determined by the BeanFactory
	 * @param eventType    the event type to check
	 * @return whether the given listener should be included in the candidates
	 * for the given event type
	 */
	protected boolean supportsEvent(Class<?> listenerType, ResolvableType eventType) {
		//解析listenerType声明的事件类型
		ResolvableType declaredEventType = GenericApplicationListenerAdapter.resolveDeclaredEventType(listenerType);
		//如果 declaredEventType 为 null || eventType 是 declaredEventType 的子类或本身
		return (declaredEventType == null || declaredEventType.isAssignableFrom(eventType));
	}

	/**
	 * <p>确定 listener 是否支持 eventType 事件类型以及  sourceType 源类型 </p>
	 * Determine whether the given listener supports the given event.
	 * <p>确定给定监听器是否支持给定事件</p>
	 * <p>The default implementation detects the {@link SmartApplicationListener}
	 * and {@link GenericApplicationListener} interfaces. In case of a standard
	 * {@link ApplicationListener}, a {@link GenericApplicationListenerAdapter}
	 * will be used to introspect the generically declared type of the target listener.
	 * <p>默认实现检测 SmartApplicationListener 和 GenericApplicationListener 接口。对于
	 * 标准的ApplicationListener,将使用 GenericApplicationAdapter来内检通用声明的</p>
	 *
	 * @param listener   the target listener to check
	 *                   -- 要检查的目标监听器
	 * @param eventType  the event type to check against
	 *                   -- 要检查的事件类型
	 * @param sourceType the source type to check against
	 *                   -- 要检查的 源类型【源类型是指产生 eventType 类型的对象的类】
	 * @return whether the given listener should be included in the candidates
	 * for the given event type
	 * -- 给定的监听器是否应该包含在给定事件类型的候选中
	 */
	protected boolean supportsEvent(
			ApplicationListener<?> listener, ResolvableType eventType, @Nullable Class<?> sourceType) {
		//如果listener是GenericApplicationListener实例，将将其强转为GenericApplicationListener对象，否则创建一个
		// 	GenericApplicationListenerAdapter 对象来封装listener作为 GenericApplicationListener 对象
		GenericApplicationListener smartListener = (listener instanceof GenericApplicationListener ?
				(GenericApplicationListener) listener : new GenericApplicationListenerAdapter(listener));
		//如果 smartListener 支持 eventType 事件类型 && smartListener 支持 sourceType 源类型【源类型是指产生 eventType 类型的对象的类】
		return (smartListener.supportsEventType(eventType) && smartListener.supportsSourceType(sourceType));
	}


	/**
	 * Cache key for ListenerRetrievers, based on event type and source type.
	 * <p>根据 事件类型 和 源类型 为 ListenerRetievers 缓存键 </p>
	 */
	private static final class ListenerCacheKey implements Comparable<ListenerCacheKey> {

		private final ResolvableType eventType;

		@Nullable
		private final Class<?> sourceType;

		public ListenerCacheKey(ResolvableType eventType, @Nullable Class<?> sourceType) {
			Assert.notNull(eventType, "Event type must not be null");
			this.eventType = eventType;
			this.sourceType = sourceType;
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof ListenerCacheKey otherKey)) {
				return false;
			}
			return (this.eventType.equals(otherKey.eventType) &&
					ObjectUtils.nullSafeEquals(this.sourceType, otherKey.sourceType));
		}

		@Override
		public int hashCode() {
			return this.eventType.hashCode() * 29 + ObjectUtils.nullSafeHashCode(this.sourceType);
		}

		@Override
		public String toString() {
			return "ListenerCacheKey [eventType = " + this.eventType + ", sourceType = " + this.sourceType + "]";
		}

		@Override
		public int compareTo(ListenerCacheKey other) {
			int result = this.eventType.toString().compareTo(other.eventType.toString());
			if (result == 0) {
				if (this.sourceType == null) {
					return (other.sourceType == null ? 0 : -1);
				}
				if (other.sourceType == null) {
					return 1;
				}
				result = this.sourceType.getName().compareTo(other.sourceType.getName());
			}
			return result;
		}
	}


	/**
	 * Helper class that encapsulates a specific set of target listeners,
	 * allowing for efficient retrieval of pre-filtered listeners.
	 * <p>An instance of this helper gets cached per event type and source type.
	 */
	private class CachedListenerRetriever {

		// 存放应用程序事件监听器，有序、不可重复
		@Nullable
		public volatile Set<ApplicationListener<?>> applicationListeners;

		// 存放应用程序事件监听器Bean名称，有序、不可重复
		@Nullable
		public volatile Set<String> applicationListenerBeans;

		@Nullable
		public Collection<ApplicationListener<?>> getApplicationListeners() {
			Set<ApplicationListener<?>> applicationListeners = this.applicationListeners;
			Set<String> applicationListenerBeans = this.applicationListenerBeans;
			if (applicationListeners == null || applicationListenerBeans == null) {
				// Not fully populated yet
				return null;
			}
			//创建指定大小的ApplicationListener 监听器集合
			List<ApplicationListener<?>> allListeners = new ArrayList<>(
					applicationListeners.size() + applicationListenerBeans.size());
			allListeners.addAll(applicationListeners);
			if (!applicationListenerBeans.isEmpty()) {
				BeanFactory beanFactory = getBeanFactory();
				for (String listenerBeanName : applicationListenerBeans) {
					try {
						allListeners.add(beanFactory.getBean(listenerBeanName, ApplicationListener.class));
					} catch (NoSuchBeanDefinitionException ex) {
						// Singleton listener instance (without backing bean definition) disappeared -
						// probably in the middle of the destruction phase
					}
				}
			}
			if (!applicationListenerBeans.isEmpty()) {
				AnnotationAwareOrderComparator.sort(allListeners);
			}
			return allListeners;
		}
	}


	/**
	 * Helper class that encapsulates a general set of target listeners.
	 * 监听器助手类
	 */
	private class DefaultListenerRetriever {

		// 存放应用程序事件监听器，有序、不可重复
		public final Set<ApplicationListener<?>> applicationListeners = new LinkedHashSet<>();

		// 存放应用程序事件监听器Bean名称，有序、不可重复
		public final Set<String> applicationListenerBeans = new LinkedHashSet<>();

		// 获取应用程序监听器
		public Collection<ApplicationListener<?>> getApplicationListeners() {
			//创建指定大小的ApplicationListener 监听器集合
			List<ApplicationListener<?>> allListeners = new ArrayList<>(
					this.applicationListeners.size() + this.applicationListenerBeans.size());
			allListeners.addAll(this.applicationListeners);
			// 若存放监听器bean name 集合不为空
			if (!this.applicationListenerBeans.isEmpty()) {
				BeanFactory beanFactory = getBeanFactory();
				for (String listenerBeanName : this.applicationListenerBeans) {
					try {
						ApplicationListener<?> listener =
								beanFactory.getBean(listenerBeanName, ApplicationListener.class);
						if (!allListeners.contains(listener)) {
							allListeners.add(listener);
						}
					} catch (NoSuchBeanDefinitionException ex) {
						// Singleton listener instance (without backing bean definition) disappeared -
						// probably in the middle of the destruction phase
					}
				}
			}
			AnnotationAwareOrderComparator.sort(allListeners);
			return allListeners;
		}
	}

}
