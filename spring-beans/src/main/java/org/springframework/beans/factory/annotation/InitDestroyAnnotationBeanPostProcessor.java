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

package org.springframework.beans.factory.annotation;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that invokes annotated init and destroy methods. Allows for an annotation
 * alternative to Spring's {@link org.springframework.beans.factory.InitializingBean}
 * and {@link org.springframework.beans.factory.DisposableBean} callback interfaces.
 *
 * <p>The actual annotation types that this post-processor checks for can be
 * configured through the {@link #setInitAnnotationType "initAnnotationType"}
 * and {@link #setDestroyAnnotationType "destroyAnnotationType"} properties.
 * Any custom annotation can be used, since there are no required annotation
 * attributes.
 *
 * <p>Init and destroy annotations may be applied to methods of any visibility:
 * public, package-protected, protected, or private. Multiple such methods
 * may be annotated, but it is recommended to only annotate one single
 * init method and destroy method, respectively.
 *
 * <p>Spring's {@link org.springframework.context.annotation.CommonAnnotationBeanPostProcessor}
 * supports the {@link jakarta.annotation.PostConstruct} and {@link jakarta.annotation.PreDestroy}
 * annotations out of the box, as init annotation and destroy annotation, respectively.
 * Furthermore, it also supports the {@link jakarta.annotation.Resource} annotation
 * for annotation-driven injection of named beans.
 *
 * @author Juergen Hoeller
 * @author Stephane Nicoll
 * @author Phillip Webb
 * @since 2.5
 * @see #setInitAnnotationType
 * @see #setDestroyAnnotationType
 */
@SuppressWarnings("serial")
public class InitDestroyAnnotationBeanPostProcessor implements DestructionAwareBeanPostProcessor,
		MergedBeanDefinitionPostProcessor, BeanRegistrationAotProcessor, PriorityOrdered, Serializable {

	private final transient LifecycleMetadata emptyLifecycleMetadata =
			new LifecycleMetadata(Object.class, Collections.emptyList(), Collections.emptyList()) {
				@Override
				public void checkConfigMembers(RootBeanDefinition beanDefinition) {
				}
				@Override
				public void invokeInitMethods(Object target, String beanName) {
				}
				@Override
				public void invokeDestroyMethods(Object target, String beanName) {
				}
				@Override
				public boolean hasDestroyMethods() {
					return false;
				}
			};


	protected transient Log logger = LogFactory.getLog(getClass());

	@Nullable
	private Class<? extends Annotation> initAnnotationType;

	@Nullable
	private Class<? extends Annotation> destroyAnnotationType;

	private int order = Ordered.LOWEST_PRECEDENCE;

	@Nullable
	private final transient Map<Class<?>, LifecycleMetadata> lifecycleMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Specify the init annotation to check for, indicating initialization
	 * methods to call after configuration of a bean.
	 * <p>Any custom annotation can be used, since there are no required
	 * annotation attributes. There is no default, although a typical choice
	 * is the {@link jakarta.annotation.PostConstruct} annotation.
	 */
	public void setInitAnnotationType(Class<? extends Annotation> initAnnotationType) {
		this.initAnnotationType = initAnnotationType;
	}

	/**
	 * Specify the destroy annotation to check for, indicating destruction
	 * methods to call when the context is shutting down.
	 * <p>Any custom annotation can be used, since there are no required
	 * annotation attributes. There is no default, although a typical choice
	 * is the {@link jakarta.annotation.PreDestroy} annotation.
	 */
	public void setDestroyAnnotationType(Class<? extends Annotation> destroyAnnotationType) {
		this.destroyAnnotationType = destroyAnnotationType;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		findInjectionMetadata(beanDefinition, beanType);
	}

	@Override
	public BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
		RootBeanDefinition beanDefinition = registeredBean.getMergedBeanDefinition();
		beanDefinition.resolveDestroyMethodIfNecessary();
		LifecycleMetadata metadata = findInjectionMetadata(beanDefinition, registeredBean.getBeanClass());
		if (!CollectionUtils.isEmpty(metadata.initMethods)) {
			String[] initMethodNames = safeMerge(beanDefinition.getInitMethodNames(), metadata.initMethods);
			beanDefinition.setInitMethodNames(initMethodNames);
		}
		if (!CollectionUtils.isEmpty(metadata.destroyMethods)) {
			String[] destroyMethodNames = safeMerge(beanDefinition.getDestroyMethodNames(), metadata.destroyMethods);
			beanDefinition.setDestroyMethodNames(destroyMethodNames);
		}
		return null;
	}

	/**
	 * 处理@PostConstruct、@PreDestroy注解
	 * @param beanDefinition
	 * @param beanType
	 * @return
	 */
	private LifecycleMetadata findInjectionMetadata(RootBeanDefinition beanDefinition, Class<?> beanType) {
		//获取生命周期相关的元数据对象，LifecycleMetadata是该类的内部类，内部持有当前的class以及对应的具有@PostConstruct、@PreDestroy注解的方法
		LifecycleMetadata metadata = findLifecycleMetadata(beanType);
		//检查配置信息
		metadata.checkConfigMembers(beanDefinition);
		return metadata;
	}

	private String[] safeMerge(@Nullable String[] existingNames, Collection<LifecycleElement> detectedElements) {
		Stream<String> detectedNames = detectedElements.stream().map(LifecycleElement::getIdentifier);
		Stream<String> mergedNames = (existingNames != null ?
				Stream.concat(Stream.of(existingNames), detectedNames) : detectedNames);
		return mergedNames.distinct().toArray(String[]::new);
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		LifecycleMetadata metadata = findLifecycleMetadata(bean.getClass());
		try {
			metadata.invokeInitMethods(bean, beanName);
		}
		catch (InvocationTargetException ex) {
			throw new BeanCreationException(beanName, "Invocation of init method failed", ex.getTargetException());
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Failed to invoke init method", ex);
		}
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@Override
	public void postProcessBeforeDestruction(Object bean, String beanName) throws BeansException {
		LifecycleMetadata metadata = findLifecycleMetadata(bean.getClass());
		try {
			metadata.invokeDestroyMethods(bean, beanName);
		}
		catch (InvocationTargetException ex) {
			String msg = "Destroy method on bean with name '" + beanName + "' threw an exception";
			if (logger.isDebugEnabled()) {
				logger.warn(msg, ex.getTargetException());
			}
			else {
				logger.warn(msg + ": " + ex.getTargetException());
			}
		}
		catch (Throwable ex) {
			logger.warn("Failed to invoke destroy method on bean with name '" + beanName + "'", ex);
		}
	}

	@Override
	public boolean requiresDestruction(Object bean) {
		return findLifecycleMetadata(bean.getClass()).hasDestroyMethods();
	}


	/**
	 * 获取LifecycleMetadata
	 *
	 * @param clazz bean的类型
	 * @return LifecycleMetadata对象，内部持有当前的class以及对应的具有@PostConstruct、@PreDestroy注解的方法
	 */
	private LifecycleMetadata findLifecycleMetadata(Class<?> clazz) {
		//如果lifecycleMetadataCache缓存为null
		if (this.lifecycleMetadataCache == null) {
			// Happens after deserialization, during destruction...
			// 创建该类型的LifecycleMetadata
			return buildLifecycleMetadata(clazz);
		}
		// Quick check on the concurrent map first, with minimal locking.
		//否则，查询缓存
		LifecycleMetadata metadata = this.lifecycleMetadataCache.get(clazz);
		//如果存在该类型的缓存，从缓存获取
		if (metadata == null) {
			synchronized (this.lifecycleMetadataCache) {
				//加锁之后再次获取，防止并发
				metadata = this.lifecycleMetadataCache.get(clazz);
				//如果metadata为null
				if (metadata == null) {
					// 构建生命周期元数据
					metadata = buildLifecycleMetadata(clazz);
					this.lifecycleMetadataCache.put(clazz, metadata);
				}
				return metadata;
			}
		}
		//如果缓存不为null，那么直接返回缓存
		return metadata;
	}


	private LifecycleMetadata buildLifecycleMetadata(final Class<?> clazz) {
		if (!AnnotationUtils.isCandidateClass(clazz, Arrays.asList(this.initAnnotationType, this.destroyAnnotationType))) {
			return this.emptyLifecycleMetadata;
		}
		// 实例化后的回调方法（@PostConstruct)
		List<LifecycleElement> initMethods = new ArrayList<>();
		//销毁前的回调方法(@PreDestroy)
		List<LifecycleElement> destroyMethods = new ArrayList<>();
		// 获取正在处理的目标类
		Class<?> targetClass = clazz;

		do {
			// 保证每一轮循环搜索到相关方法
			final List<LifecycleElement> currInitMethods = new ArrayList<>();
			final List<LifecycleElement> currDestroyMethods = new ArrayList<>();
			//反射获取当前类中的所有方法
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				// 当前方法包含initAnnotationType 类型 （@PostConstruct)
				if (this.initAnnotationType != null && method.isAnnotationPresent(this.initAnnotationType)) {
					LifecycleElement element = new LifecycleElement(method);
					currInitMethods.add(element);
					if (logger.isTraceEnabled()) {
						logger.trace("Found init method on class [" + clazz.getName() + "]: " + method);
					}
				}
				// 当前方法包含destroyAnnotationType 类型 (@PreDestroy)
				if (this.destroyAnnotationType != null && method.isAnnotationPresent(this.destroyAnnotationType)) {
					currDestroyMethods.add(new LifecycleElement(method));
					if (logger.isTraceEnabled()) {
						logger.trace("Found destroy method on class [" + clazz.getName() + "]: " + method);
					}
				}
			});

			//currInitMethods集合整体添加到initMethods集合的开头
			initMethods.addAll(0, currInitMethods);
			//currDestroyMethods集合整体添加到destroyMethods集合的开头
			destroyMethods.addAll(currDestroyMethods);
			//获取下一个目标类型，是当前类型的父类型
			targetClass = targetClass.getSuperclass();
		}
		//如果目标类型不为null并且不是Object.class类型，那么继续循环，否则结束循环
		while (targetClass != null && targetClass != Object.class);
		//如果initMethods和destroyMethods都是空集合，那么返回一个空的LifecycleMetadata实例
		//否则返回一个新LifecycleMetadata，包含当前的class以及对应的找到的initMethods和destroyMethods
		return (initMethods.isEmpty() && destroyMethods.isEmpty() ? this.emptyLifecycleMetadata :
				new LifecycleMetadata(clazz, initMethods, destroyMethods));
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// Rely on default serialization; just initialize state after deserialization.
		ois.defaultReadObject();

		// Initialize transient fields.
		this.logger = LogFactory.getLog(getClass());
	}


	/**
	 * Class representing information about annotated init and destroy methods.
	 */
	private class LifecycleMetadata {

		private final Class<?> targetClass;

		private final Collection<LifecycleElement> initMethods;

		private final Collection<LifecycleElement> destroyMethods;

		@Nullable
		private volatile Set<LifecycleElement> checkedInitMethods;

		@Nullable
		private volatile Set<LifecycleElement> checkedDestroyMethods;

		public LifecycleMetadata(Class<?> targetClass, Collection<LifecycleElement> initMethods,
				Collection<LifecycleElement> destroyMethods) {

			this.targetClass = targetClass;
			this.initMethods = initMethods;
			this.destroyMethods = destroyMethods;
		}

		/**
		 * 检查配置，设置相关的方法到checkedInitMethods和checkedDestroyMethods中，后续直接调用
		 * 同时设置到mbd的externallyManagedInitMethods中，防止重复调用同一个方法
		 * @param beanDefinition 当前bean定义
		 */
		public void checkConfigMembers(RootBeanDefinition beanDefinition) {
			//创建被检查的初始化回调方法集合
			Set<LifecycleElement> checkedInitMethods = new LinkedHashSet<>(this.initMethods.size());
			//遍历检查initMethods
			for (LifecycleElement element : this.initMethods) {
				//获取标识符
				String methodIdentifier = element.getIdentifier();
				//如果mbd的externallyManagedInitMethods不包含当前回调方法
				if (!beanDefinition.isExternallyManagedInitMethod(methodIdentifier)) {
					//设置到mbd的externallyManagedInitMethods中
					beanDefinition.registerExternallyManagedInitMethod(methodIdentifier);
					//设置到checkedInitMethods中
					checkedInitMethods.add(element);
					if (logger.isTraceEnabled()) {
						logger.trace("Registered init method on class [" + this.targetClass.getName() + "]: " + element);
					}
				}
			}
			Set<LifecycleElement> checkedDestroyMethods = new LinkedHashSet<>(this.destroyMethods.size());
			for (LifecycleElement element : this.destroyMethods) {
				//获取标识符
				String methodIdentifier = element.getIdentifier();
				//如果mbd的externallyManagedInitMethods不包含当前回调方法
				if (!beanDefinition.isExternallyManagedDestroyMethod(methodIdentifier)) {
					//设置到mbd的externallyManagedInitMethods中
					beanDefinition.registerExternallyManagedDestroyMethod(methodIdentifier);
					//设置到checkedInitMethods中
					checkedDestroyMethods.add(element);
					if (logger.isTraceEnabled()) {
						logger.trace("Registered destroy method on class [" + this.targetClass.getName() + "]: " + element);
					}
				}
			}
			this.checkedInitMethods = checkedInitMethods;
			this.checkedDestroyMethods = checkedDestroyMethods;
		}

		public void invokeInitMethods(Object target, String beanName) throws Throwable {
			Collection<LifecycleElement> checkedInitMethods = this.checkedInitMethods;
			Collection<LifecycleElement> initMethodsToIterate =
					(checkedInitMethods != null ? checkedInitMethods : this.initMethods);
			if (!initMethodsToIterate.isEmpty()) {
				for (LifecycleElement element : initMethodsToIterate) {
					if (logger.isTraceEnabled()) {
						logger.trace("Invoking init method on bean '" + beanName + "': " + element.getMethod());
					}
					element.invoke(target);
				}
			}
		}

		public void invokeDestroyMethods(Object target, String beanName) throws Throwable {
			Collection<LifecycleElement> checkedDestroyMethods = this.checkedDestroyMethods;
			Collection<LifecycleElement> destroyMethodsToUse =
					(checkedDestroyMethods != null ? checkedDestroyMethods : this.destroyMethods);
			if (!destroyMethodsToUse.isEmpty()) {
				for (LifecycleElement element : destroyMethodsToUse) {
					if (logger.isTraceEnabled()) {
						logger.trace("Invoking destroy method on bean '" + beanName + "': " + element.getMethod());
					}
					element.invoke(target);
				}
			}
		}

		public boolean hasDestroyMethods() {
			Collection<LifecycleElement> checkedDestroyMethods = this.checkedDestroyMethods;
			Collection<LifecycleElement> destroyMethodsToUse =
					(checkedDestroyMethods != null ? checkedDestroyMethods : this.destroyMethods);
			return !destroyMethodsToUse.isEmpty();
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 * 一个LifecycleElement对象封装了一个回调方法，及其标识符，标识符用于方法重复调用
	 */
	private static class LifecycleElement {

		private final Method method;

		private final String identifier;

		public LifecycleElement(Method method) {
			//如果方法参数不为0，那么抛出异常，从这里可知，初始化和销毁回调方法都不能有参数
			if (method.getParameterCount() != 0) {
				throw new IllegalStateException("Lifecycle method annotation requires a no-arg method: " + method);
			}
			this.method = method;
			//计算identifier
			//如果是私有方法，则标识符为：该方法的全路径名，如果是非私有方法，则标识符为该方法简单名字
			this.identifier = (Modifier.isPrivate(method.getModifiers()) ?
					ClassUtils.getQualifiedMethodName(method) : method.getName());
		}

		public Method getMethod() {
			return this.method;
		}

		public String getIdentifier() {
			return this.identifier;
		}

		public void invoke(Object target) throws Throwable {
			ReflectionUtils.makeAccessible(this.method);
			this.method.invoke(target, (Object[]) null);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof LifecycleElement otherElement)) {
				return false;
			}
			return (this.identifier.equals(otherElement.identifier));
		}

		@Override
		public int hashCode() {
			return this.identifier.hashCode();
		}
	}

}
