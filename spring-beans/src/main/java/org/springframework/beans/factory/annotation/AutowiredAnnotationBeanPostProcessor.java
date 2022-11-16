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

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aot.generate.AccessControl;
import org.springframework.aot.generate.GeneratedClass;
import org.springframework.aot.generate.GeneratedMethod;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.aot.AutowiredArgumentsCodeGenerator;
import org.springframework.beans.factory.aot.AutowiredFieldValueResolver;
import org.springframework.beans.factory.aot.AutowiredMethodArgumentsResolver;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationCode;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.javapoet.ClassName;
import org.springframework.javapoet.CodeBlock;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor BeanPostProcessor}
 * implementation that autowires annotated fields, setter methods, and arbitrary
 * config methods. Such members to be injected are detected through annotations:
 * by default, Spring's {@link Autowired @Autowired} and {@link Value @Value}
 * annotations.
 *
 * <p>Also supports the common {@link jakarta.inject.Inject @Inject} annotation,
 * if available, as a direct alternative to Spring's own {@code @Autowired}.
 * Additionally, it retains support for the {@code javax.inject.Inject} variant
 * dating back to the original JSR-330 specification (as known from Java EE 6-8).
 *
 * <h3>Autowired Constructors</h3>
 * <p>Only one constructor of any given bean class may declare this annotation with
 * the 'required' attribute set to {@code true}, indicating <i>the</i> constructor
 * to autowire when used as a Spring bean. Furthermore, if the 'required' attribute
 * is set to {@code true}, only a single constructor may be annotated with
 * {@code @Autowired}. If multiple <i>non-required</i> constructors declare the
 * annotation, they will be considered as candidates for autowiring. The constructor
 * with the greatest number of dependencies that can be satisfied by matching beans
 * in the Spring container will be chosen. If none of the candidates can be satisfied,
 * then a primary/default constructor (if present) will be used. If a class only
 * declares a single constructor to begin with, it will always be used, even if not
 * annotated. An annotated constructor does not have to be public.
 *
 * <h3>Autowired Fields</h3>
 * <p>Fields are injected right after construction of a bean, before any
 * config methods are invoked. Such a config field does not have to be public.
 *
 * <h3>Autowired Methods</h3>
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a
 * general config method. Config methods do not have to be public.
 *
 * <h3>Annotation Config vs. XML Config</h3>
 * <p>A default {@code AutowiredAnnotationBeanPostProcessor} will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom {@code AutowiredAnnotationBeanPostProcessor} bean definition.
 *
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection;
 * thus the latter configuration will override the former for properties wired through
 * both approaches.
 *
 * <h3>{@literal @}Lookup Methods</h3>
 * <p>In addition to regular injection points as discussed above, this post-processor
 * also handles Spring's {@link Lookup @Lookup} annotation which identifies lookup
 * methods to be replaced by the container at runtime. This is essentially a type-safe
 * version of {@code getBean(Class, args)} and {@code getBean(String, args)}.
 * See {@link Lookup @Lookup's javadoc} for details.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @author Phillip Webb
 * @since 2.5
 * @see #setAutowiredAnnotationType
 * @see Autowired
 * @see Value
 */
public class AutowiredAnnotationBeanPostProcessor implements SmartInstantiationAwareBeanPostProcessor,
		MergedBeanDefinitionPostProcessor, BeanRegistrationAotProcessor, PriorityOrdered, BeanFactoryAware {

	protected final Log logger = LogFactory.getLog(getClass());

	private final Set<Class<? extends Annotation>> autowiredAnnotationTypes = new LinkedHashSet<>(4);

	private String requiredParameterName = "required";

	private boolean requiredParameterValue = true;

	private int order = Ordered.LOWEST_PRECEDENCE - 2;

	@Nullable
	private ConfigurableListableBeanFactory beanFactory;

	private final Set<String> lookupMethodsChecked = Collections.newSetFromMap(new ConcurrentHashMap<>(256));

	private final Map<Class<?>, Constructor<?>[]> candidateConstructorsCache = new ConcurrentHashMap<>(256);

	private final Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new {@code AutowiredAnnotationBeanPostProcessor} for Spring's
	 * standard {@link Autowired @Autowired} and {@link Value @Value} annotations.
	 * <p>Also supports the common {@link jakarta.inject.Inject @Inject} annotation,
	 * if available, as well as the original {@code javax.inject.Inject} variant.
	 */
	@SuppressWarnings("unchecked")
	public AutowiredAnnotationBeanPostProcessor() {
		//后置处理器将处理@Autowire注解
		this.autowiredAnnotationTypes.add(Autowired.class);
		//后置处理器将处理@Value注解
		this.autowiredAnnotationTypes.add(Value.class);

		try {
			//后置处理器将处理javax.inject.Inject JSR-330注解
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("jakarta.inject.Inject", AutowiredAnnotationBeanPostProcessor.class.getClassLoader()));
			logger.trace("'jakarta.inject.Inject' annotation found and supported for autowiring");
		}
		catch (ClassNotFoundException ex) {
			// jakarta.inject API not available - simply skip.
		}

		try {
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("javax.inject.Inject", AutowiredAnnotationBeanPostProcessor.class.getClassLoader()));
			logger.trace("'javax.inject.Inject' annotation found and supported for autowiring");
		}
		catch (ClassNotFoundException ex) {
			// javax.inject API not available - simply skip.
		}
	}


	/**
	 * Set the 'autowired' annotation type, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as the common {@code @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationType(Class<? extends Annotation> autowiredAnnotationType) {
		Assert.notNull(autowiredAnnotationType, "'autowiredAnnotationType' must not be null");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.add(autowiredAnnotationType);
	}

	/**
	 * Set the 'autowired' annotation types, to be used on constructors, fields,
	 * setter methods, and arbitrary config methods.
	 * <p>The default autowired annotation types are the Spring-provided
	 * {@link Autowired @Autowired} and {@link Value @Value} annotations as well
	 * as the common {@code @Inject} annotation, if available.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation types to indicate that a member is supposed
	 * to be autowired.
	 */
	public void setAutowiredAnnotationTypes(Set<Class<? extends Annotation>> autowiredAnnotationTypes) {
		Assert.notEmpty(autowiredAnnotationTypes, "'autowiredAnnotationTypes' must not be empty");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.addAll(autowiredAnnotationTypes);
	}

	/**
	 * Set the name of an attribute of the annotation that specifies whether it is required.
	 * @see #setRequiredParameterValue(boolean)
	 */
	public void setRequiredParameterName(String requiredParameterName) {
		this.requiredParameterName = requiredParameterName;
	}

	/**
	 * Set the boolean value that marks a dependency as required.
	 * <p>For example if using 'required=true' (the default), this value should be
	 * {@code true}; but if using 'optional=false', this value should be {@code false}.
	 * @see #setRequiredParameterName(String)
	 */
	public void setRequiredParameterValue(boolean requiredParameterValue) {
		this.requiredParameterValue = requiredParameterValue;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory: " + beanFactory);
		}
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}


	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		findInjectionMetadata(beanName, beanType, beanDefinition);
	}

	@Override
	public BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
		Class<?> beanClass = registeredBean.getBeanClass();
		String beanName = registeredBean.getBeanName();
		RootBeanDefinition beanDefinition = registeredBean.getMergedBeanDefinition();
		InjectionMetadata metadata = findInjectionMetadata(beanName, beanClass, beanDefinition);
		Collection<AutowiredElement> autowiredElements = getAutowiredElements(metadata);
		if (!ObjectUtils.isEmpty(autowiredElements)) {
			return new AotContribution(beanClass, autowiredElements, getAutowireCandidateResolver());
		}
		return null;
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Collection<AutowiredElement> getAutowiredElements(InjectionMetadata metadata) {
		return (Collection) metadata.getInjectedElements();
	}

	@Nullable
	private AutowireCandidateResolver getAutowireCandidateResolver() {
		if (this.beanFactory instanceof DefaultListableBeanFactory lbf) {
			return lbf.getAutowireCandidateResolver();
		}
		return null;
	}

	private InjectionMetadata findInjectionMetadata(String beanName, Class<?> beanType, RootBeanDefinition beanDefinition) {
		//获取指定类中autowire相关注解的元信息
		InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
		//对Bean的属性进行自动注入
		metadata.checkConfigMembers(beanDefinition);
		return metadata;
	}

	@Override
	public void resetBeanDefinition(String beanName) {
		this.lookupMethodsChecked.remove(beanName);
		this.injectionMetadataCache.remove(beanName);
	}

	@Override
	public Class<?> determineBeanType(Class<?> beanClass, String beanName) throws BeanCreationException {
		checkLookupMethods(beanClass, beanName);

		// Pick up subclass with fresh lookup method override from above
		if (this.beanFactory instanceof AbstractAutowireCapableBeanFactory aacbf) {
			RootBeanDefinition mbd = (RootBeanDefinition) this.beanFactory.getMergedBeanDefinition(beanName);
			if (mbd.getFactoryMethodName() == null && mbd.hasBeanClass()) {
				return aacbf.getInstantiationStrategy().getActualBeanClass(mbd, beanName, this.beanFactory);
			}
		}
		return beanClass;
	}

	@Override
	@Nullable
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, final String beanName)
			throws BeanCreationException {

		// @Lookup注解检查
		checkLookupMethods(beanClass, beanName);

		// Quick check on the concurrent map first, with minimal locking.
		// 1.构造函数解析，首先检查是否存在于缓存中
		Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
		/*
		 * 如果缓存为null，说明没解析过，那么需要解析
		 * 解析之后会将该类型Class以及对应的结果加入candidateConstructorsCache缓存，后续同类型再来时不会再次解析
		 */
		if (candidateConstructors == null) {
			// Fully synchronized resolution now...
			// 2.加锁进行操作
			synchronized (this.candidateConstructorsCache) {
				// 3.再次检查缓存，双重检测
				candidateConstructors = this.candidateConstructorsCache.get(beanClass);
				if (candidateConstructors == null) {
					// 存放原始的构造函数（候选者）
					Constructor<?>[] rawCandidates;
					try {
						// 4.获取beanClass声明的构造函数（如果没有声明，会返回一个默认的无参构造函数）
						rawCandidates = beanClass.getDeclaredConstructors();
					}
					catch (Throwable ex) {
						throw new BeanCreationException(beanName,
								"Resolution of declared constructors on bean Class [" + beanClass.getName() +
								"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
					}
					// 存放使用了@Autowire注解的构造函数
					List<Constructor<?>> candidates = new ArrayList<>(rawCandidates.length);
					// 存放使用了@Autowire注解，并且require=true的构造函数
					Constructor<?> requiredConstructor = null;
					// 存放默认的构造函数
					Constructor<?> defaultConstructor = null;
					Constructor<?> primaryConstructor = BeanUtils.findPrimaryConstructor(beanClass);
					int nonSyntheticConstructors = 0;
					// 5.遍历原始的构造函数候选者
					for (Constructor<?> candidate : rawCandidates) {
						if (!candidate.isSynthetic()) {
							nonSyntheticConstructors++;
						}
						else if (primaryConstructor != null) {
							continue;
						}
						// 6.获取候选者的注解属性
						MergedAnnotation<?> ann = findAutowiredAnnotation(candidate);
						if (ann == null) {
							// 7.如果没有从候选者找到注解，则尝试解析beanClass的原始类（针对CGLIB代理）
							Class<?> userClass = ClassUtils.getUserClass(beanClass);
							if (userClass != beanClass) {
								try {
									Constructor<?> superCtor =
											userClass.getDeclaredConstructor(candidate.getParameterTypes());
									ann = findAutowiredAnnotation(superCtor);
								}
								catch (NoSuchMethodException ex) {
									// Simply proceed, no equivalent superclass constructor found...
								}
							}
						}
						// 8.如果该候选者使用了@Autowire注解
						if (ann != null) {
							// 8.1 之前已经存在使用@Autowired(required = true)的构造函数，则不能存在其他使用@Autowire注解的构造函数，否则抛异常
							if (requiredConstructor != null) {
								throw new BeanCreationException(beanName,
										"Invalid autowire-marked constructor: " + candidate +
										". Found constructor with 'required' Autowired annotation already: " +
										requiredConstructor);
							}

							// 8.2 获取注解的require属性值
							boolean required = determineRequiredStatus(ann);
							if (required) {
								if (!candidates.isEmpty()) {
									// 8.3 如果当前候选者是@Autowired(required = true)，则之前不能存在其他使用@Autowire注解的构造函数，否则抛异常
									throw new BeanCreationException(beanName,
											"Invalid autowire-marked constructors: " + candidates +
											". Found constructor with 'required' Autowired annotation: " +
											candidate);
								}
								// 8.4 如果该候选者使用的注解的required属性为true，赋值给requiredConstructor
								requiredConstructor = candidate;
							}
							// 8.5 将使用了@Autowire注解的候选者添加到candidates
							candidates.add(candidate);
						}
						else if (candidate.getParameterCount() == 0) {
							// 8.6 如果没有使用注解，并且没有参数，则为默认的构造函数
							defaultConstructor = candidate;
						}
					}
					// 9.如果存在使用了@Autowire注解的构造函数
					if (!candidates.isEmpty()) {
						// Add default constructor to list of optional constructors, as fallback.
						// 9.1 但是没有使用了@Autowire注解并且required属性为true的构造函数
						if (requiredConstructor == null) {
							if (defaultConstructor != null) {
								// 9.2 如果存在默认的构造函数，则将默认的构造函数添加到candidates
								candidates.add(defaultConstructor);
							}
							else if (candidates.size() == 1 && logger.isInfoEnabled()) {
								logger.info("Inconsistent constructor declaration on bean with name '" + beanName +
										"': single autowire-marked constructor flagged as optional - " +
										"this constructor is effectively required since there is no " +
										"default constructor to fall back to: " + candidates.get(0));
							}
						}
						// 9.3 将所有的candidates当作候选者
						candidateConstructors = candidates.toArray(new Constructor<?>[0]);
					}
					else if (rawCandidates.length == 1 && rawCandidates[0].getParameterCount() > 0) {
						// 10.如果candidates为空 && beanClass只有一个声明的构造函数（非默认构造函数），则将该声明的构造函数作为候选者
						candidateConstructors = new Constructor<?>[] {rawCandidates[0]};
					}
					else if (nonSyntheticConstructors == 2 && primaryConstructor != null &&
							defaultConstructor != null && !primaryConstructor.equals(defaultConstructor)) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor, defaultConstructor};
					}
					else if (nonSyntheticConstructors == 1 && primaryConstructor != null) {
						candidateConstructors = new Constructor<?>[] {primaryConstructor};
					}
					else {
						// 11.否则返回一个空的Constructor对象
						candidateConstructors = new Constructor<?>[0];
					}
					// 12.将beanClass的构造函数解析结果放到缓存
					this.candidateConstructorsCache.put(beanClass, candidateConstructors);
				}
			}
		}
		// 13.返回解析的构造函数
		return (candidateConstructors.length > 0 ? candidateConstructors : null);

	}

	private void checkLookupMethods(Class<?> beanClass, final String beanName) throws BeanCreationException {
		if (!this.lookupMethodsChecked.contains(beanName)) {
			//确定给定类是否是承载指定注释的候选项（在类型、方法或字段级别）。
			//如果任何一个注解的全路径名都不是以"java."开始，并且该Class全路径名以"start."开始，或者Class的类型为Ordered.class，那么返回false，否则其他情况都返回true
			if (AnnotationUtils.isCandidateClass(beanClass, Lookup.class)) {
				try {
					Class<?> targetClass = beanClass;
					do {
						/*
						 * 循环过滤所有的方法（不包括构造器）
						 */
						ReflectionUtils.doWithLocalMethods(targetClass, method -> {
							//尝试获取方法上的@Lookup注解
							Lookup lookup = method.getAnnotation(Lookup.class);
							//如果存在@Lookup注解
							if (lookup != null) {
								Assert.state(this.beanFactory != null, "No BeanFactory available");
								//创建一个LookupOverride对象存入当前beanName的mbd中
								LookupOverride override = new LookupOverride(method, lookup.value());
								try {
									RootBeanDefinition mbd = (RootBeanDefinition)
											this.beanFactory.getMergedBeanDefinition(beanName);
									//加入methodOverrides内部的overrides集合中
									mbd.getMethodOverrides().addOverride(override);
								}
								catch (NoSuchBeanDefinitionException ex) {
									throw new BeanCreationException(beanName,
											"Cannot apply @Lookup to beans without corresponding bean definition");
								}
							}
						});
						//获取父类Class
						targetClass = targetClass.getSuperclass();
					}
					while (targetClass != null && targetClass != Object.class);

				}
				catch (IllegalStateException ex) {
					throw new BeanCreationException(beanName, "Lookup method resolution failed", ex);
				}
			}
			//检查过的beanName加入到lookupMethodsChecked中，无论有没有@Lookup注解
			this.lookupMethodsChecked.add(beanName);
		}
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
		try {
			metadata.inject(bean, beanName, pvs);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
		}
		return pvs;
	}

	/**
	 * 'Native' processing method for direct calls with an arbitrary target instance,
	 * resolving all of its fields and methods which are annotated with one of the
	 * configured 'autowired' annotation types.
	 * @param bean the target instance to process
	 * @throws BeanCreationException if autowiring failed
	 * @see #setAutowiredAnnotationTypes(Set)
	 */
	public void processInjection(Object bean) throws BeanCreationException {
		Class<?> clazz = bean.getClass();
		InjectionMetadata metadata = findAutowiringMetadata(clazz.getName(), clazz, null);
		try {
			metadata.inject(bean, null, null);
		}
		catch (BeanCreationException ex) {
			throw ex;
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					"Injection of autowired dependencies failed for class [" + clazz + "]", ex);
		}
	}

	//获取给定类的autowire相关注解元信息
	private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, @Nullable PropertyValues pvs) {
		// Fall back to class name as cache key, for backwards compatibility with custom callers.
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
		// Quick check on the concurrent map first, with minimal locking.
		//首先从容器中查找是否有给定类的autowire相关注解元信息
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
		if (InjectionMetadata.needsRefresh(metadata, clazz)) {
			synchronized (this.injectionMetadataCache) {
				metadata = this.injectionMetadataCache.get(cacheKey);
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {
					if (metadata != null) {
						//解析给定类autowire相关注解元信息
						metadata.clear(pvs);
					}
					//解析给定类autowire相关注解元信息
					metadata = buildAutowiringMetadata(clazz);
					//将得到的给定类autowire相关注解元信息存储在容器缓存中
					this.injectionMetadataCache.put(cacheKey, metadata);
				}
			}
		}
		return metadata;
	}

	//解析给定类autowire相关注解元信息
	private InjectionMetadata buildAutowiringMetadata(Class<?> clazz) {
		if (!AnnotationUtils.isCandidateClass(clazz, this.autowiredAnnotationTypes)) {
			return InjectionMetadata.EMPTY;
		}
		//创建一个存放注解元信息的集合
		List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
		Class<?> targetClass = clazz;
		//递归遍历当前类及其所有基类，解析全部注解元信息
		do {
			//创建一个存储当前正在处理类注解元信息的集合
			final List<InjectionMetadata.InjectedElement> currElements = new ArrayList<>();
			//利用JDK反射机制获取给定类中所有的声明字段，获取字段上的注解信息
			ReflectionUtils.doWithLocalFields(targetClass, field -> {
				//获取给定字段上的注解
				MergedAnnotation<?> ann = findAutowiredAnnotation(field);
				if (ann != null) {
					//如果给定字段是静态的(static)，则直接遍历下一个字段
					if (Modifier.isStatic(field.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static fields: " + field);
						}
						return;
					}
					//判断注解的required属性值是否有效
					boolean required = determineRequiredStatus(ann);
					//将当前字段元信息封装，添加在返回的集合中
					currElements.add(new AutowiredFieldElement(field, required));
				}
			});
			//利用JDK反射机制获取给定类中所有的声明方法，获取方法上的注解信息
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					return;
				}
				//获取给定方法上的所有注解
				MergedAnnotation<?> ann = findAutowiredAnnotation(bridgedMethod);
				if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					//如果方法是静态的，则直接遍历下一个方法
					if (Modifier.isStatic(method.getModifiers())) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation is not supported on static methods: " + method);
						}
						return;
					}
					//如果方法的参数列表为空
					if (method.getParameterCount() == 0) {
						if (logger.isInfoEnabled()) {
							logger.info("Autowired annotation should only be used on methods with parameters: " +
									method);
						}
					}
					//判断注解的required属性值是否有效
					boolean required = determineRequiredStatus(ann);
					//获取当前方法的属性描述符，即方法是可读的(readable)getter方法，还是可写的(writeable)setter方法
					PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
					//将方法元信息封装添加到返回的元信息集合中
					currElements.add(new AutowiredMethodElement(method, required, pd));
				}
			});
			//将当前类的注解元信息存放到注解元信息集合中
			elements.addAll(0, currElements);
			//获取给定类的父类
			targetClass = targetClass.getSuperclass();
		}
		//如果给定类有基类，并且基类不是Object，则递归获取其基类的元信息
		while (targetClass != null && targetClass != Object.class);

		return InjectionMetadata.forElements(elements, clazz);
	}


	/**
	 * 获取构造器、方法、字段上的@Autowired、@Value、@Inject注解的MergedAnnotation
	 * 按照顺序查找，因此优先级@Autowired > @Value > @Inject
	 *
	 * @param ao 指定元素，可能是构造器、方法、字段
	 * @return @Autowired、@Value、@Inject注解的MergedAnnotation，没有就返回null
	 */
	@Nullable
	private MergedAnnotation<?> findAutowiredAnnotation(AccessibleObject ao) {
		//创建一个新的MergedAnnotations实例，其中包含指定元素中的所有注解和元注解
		MergedAnnotations annotations = MergedAnnotations.from(ao);
		//遍历是否存在autowiredAnnotationTypes中的类型的注解：按照顺序为@Autowired、@Value、@Inject
		//autowiredAnnotationTypes中的元素在AutowiredAnnotationBeanPostProcessor初始化时就添加进去了
		for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
			//尝试获取该类型的注解
			MergedAnnotation<?> annotation = annotations.get(type);
			//如果存在，那么直接返回该注解的MergedAnnotation
			if (annotation.isPresent()) {
				return annotation;
			}
		}
		//返回null
		return null;
	}


	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 */
	protected boolean determineRequiredStatus(MergedAnnotation<?> ann) {
		return (ann.getValue(this.requiredParameterName).isEmpty() ||
				this.requiredParameterValue == ann.getBoolean(this.requiredParameterName));
	}

	/**
	 * Register the specified bean as dependent on the autowired beans.
	 */
	private void registerDependentBeans(@Nullable String beanName, Set<String> autowiredBeanNames) {
		if (beanName != null) {
			// 1.遍历所有autowiredBeanNames
			for (String autowiredBeanName : autowiredBeanNames) {
				if (this.beanFactory != null && this.beanFactory.containsBean(autowiredBeanName)) {
					// 2.如果autowiredBeanName在BeanFactory中存在，则注册依赖关系到缓存（beanName 依赖 autowiredBeanName）
					this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Autowiring by type from bean name '" + beanName +
							"' to bean named '" + autowiredBeanName + "'");
				}
			}
		}
	}

	/**
	 * Resolve the specified cached method argument or field value.
	 */
	@Nullable
	private Object resolvedCachedArgument(@Nullable String beanName, @Nullable Object cachedArgument) {
		//如果cachedArgument不为null
		if (cachedArgument instanceof DependencyDescriptor descriptor) {
			Assert.state(this.beanFactory != null, "No BeanFactory available");
			/*
			 * 调用resolveDependency方法根据类型解析依赖，返回找到的依赖
			 *
			 * 这里的descriptor是此前cachedFieldValue缓存起来的，如果是ShortcutDependencyDescriptor类型，那么
			 * 在resolveDependency方法内部的doResolveDependency方法开头就会尝试调用resolveShortcut方法快速获取依赖，
			 * 而这个方法在DependencyDescriptor中默认返回null，而ShortcutDependencyDescriptor则重写了该方法，
			 * 从给定工厂中快速获取具有指定beanName和type的bean实例。因此，上面的ShortcutDependencyDescriptor用于快速获取依赖项
			 */
			return this.beanFactory.resolveDependency(descriptor, beanName, null, null);
		}
		else {
			return cachedArgument;
		}
	}


	/**
	 * Base class representing injection information.
	 */
	private abstract static class AutowiredElement extends InjectionMetadata.InjectedElement {

		protected final boolean required;

		protected AutowiredElement(Member member, @Nullable PropertyDescriptor pd, boolean required) {
			super(member, pd);
			this.required = required;
		}
	}


	/**
	 * Class representing injection information about an annotated field.
	 * 表示自动注入注解（@Autowired、@Value、@Inject）字段的注入信息的类。
	 */
	private class AutowiredFieldElement extends AutowiredElement {

		/**
		 * 是否以缓存，即该注入点是否已被解析过
		 */
		private volatile boolean cached;

		/**
		 * 缓存的已被解析的字段值，可能是DependencyDescriptor或者ShortcutDependencyDescriptor或者null
		 */
		@Nullable
		private volatile Object cachedFieldValue;

		public AutowiredFieldElement(Field field, boolean required) {
			super(field, null, required);
		}

		/**
		 * InjectedElement的子类内部类AutowiredFieldElement重写的方法,完成字段注入点的注入操作，包括@Autowired、@Value、@Inject注解的解析
		 *
		 * @param bean     需要进行注入的目标实例
		 * @param beanName beanName
		 * @param pvs      已找到的属性值数组，用于防止重复注入
		 * @throws Throwable
		 */
		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {
			Field field = (Field) this.member;
			Object value;
			//如果字段的值有缓存
			if (this.cached) {
				try {
					//从缓存中获取字段值value
					value = resolvedCachedArgument(beanName, this.cachedFieldValue);
				} catch (NoSuchBeanDefinitionException ex) {
					// Unexpected removal of target bean for cached argument -> re-resolve
					value = resolveFieldValue(field, bean, beanName);
				}
			}
			//没有缓存
			else {
				value = resolveFieldValue(field, bean, beanName);
			}
			//如果字段值不为null
			if (value != null) {
				//显式使用JDK的反射机制，设置自动的访问控制权限为允许访问
				ReflectionUtils.makeAccessible(field);
				//为字段赋值
				field.set(bean, value);
			}
		}

		@Nullable
		private Object resolveFieldValue(Field field, Object bean, @Nullable String beanName) {
			//获取字段描述符，这里的required属性就是@Autowired注解的required属性值，没有就设置默认true
			DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
			desc.setContainingClass(bean.getClass());
			//自动注入的beanName
			Set<String> autowiredBeanNames = new LinkedHashSet<>(1);
			Assert.state(beanFactory != null, "No BeanFactory available");
			//获取容器中的类型转换器
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			Object value;
			try {
				/*
				 * 调用resolveDependency方法根据类型解析依赖，返回找到的依赖，查找规则在之前讲过，注意这里的required是可以设置的
				 * 如果required设置为false，那么没找到依赖将不会抛出异常
				 * 如果找到多个依赖，那么会尝试查找最合适的依赖，就掉调用determineAutowireCandidate方法，此前就讲过了
				 * 在最后一步会尝试根据name进行匹配，如果还是不能筛选出合适的依赖，那么抛出异常
				 * 这就是byType优先于byName的原理，实际上一个resolveDependency方法就完成了
				 */
				value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
			} catch (BeansException ex) {
				throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(field), ex);
			}
			//线程同步，确保容器中数据一致性
			synchronized (this) {
				//如果字段的值没有缓存
				if (!this.cached) {
					//字段值不为null，并且required属性为true
					Object cachedFieldValue = null;
					if (value != null || this.required) {
						cachedFieldValue = desc;
						//为指定Bean注册依赖Bean
						registerDependentBeans(beanName, autowiredBeanNames);
						if (autowiredBeanNames.size() == 1) {
							String autowiredBeanName = autowiredBeanNames.iterator().next();
							//如果容器中有指定名称的Bean对象
							if (beanFactory.containsBean(autowiredBeanName) &&
									//依赖对象类型和字段类型匹配，默认按类型注入
									beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
								//创建一个依赖对象的引用，同时缓存
								cachedFieldValue = new ShortcutDependencyDescriptor(
										desc, autowiredBeanName, field.getType());
							}
						}
					}
					this.cachedFieldValue = cachedFieldValue;
					//cached设置为true，表示已解析过，并且设置了缓存
					this.cached = true;
				}
			}
			return value;
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 * 表示自动注入注解（@Autowired、@Value、@Inject）方法的注入信息的类。
	 */
	private class AutowiredMethodElement extends AutowiredElement {

		/**
		 * 是否以缓存，即该注入点是否已被解析过
		 */
		private volatile boolean cached;


		/**
		 * 缓存的已被解析的方法参数值数组，单个元素可能是DependencyDescriptor或者ShortcutDependencyDescriptor
		 * 或者cachedMethodArguments就是null
		 */
		@Nullable
		private volatile Object[] cachedMethodArguments;

		public AutowiredMethodElement(Method method, boolean required, @Nullable PropertyDescriptor pd) {
			super(method, pd, required);
		}

		@Override
		protected void inject(Object bean, @Nullable String beanName, @Nullable PropertyValues pvs) throws Throwable {

			/*
			 * 检查是否可以跳过该"属性"注入，也就是看此前找到的pvs中是否存在该名字的属性，如果存在就跳过，不存在就不跳过
			 * 这里可以发现：
			 *  setter方法的注入流程的优先级为：XML手动设置的property > XML设置自动注入的找到的property -> 注解设置的property
			 *  前面的流程中没找到指定名称的property时，当前流程才会查找property
			 */
			if (checkPropertySkipping(pvs)) {
				return;
			}
			//获取方法
			Method method = (Method) this.member;
			Object[] arguments;
			//如果已被缓存过，只要调用过一次该方法，那么cached将被设置为true，后续都走resolveCachedArguments
			if (this.cached) {
				try {
					// 走缓存的逻辑，获取需要注入的依赖项数组
					arguments = resolveCachedArguments(beanName);
				}
				catch (NoSuchBeanDefinitionException ex) {
					// Unexpected removal of target bean for cached argument -> re-resolve
					arguments = resolveMethodArguments(method, bean, beanName);
				}
			}
			else {
				arguments = resolveMethodArguments(method, bean, beanName);
			}
			//如果arguments不为null
			if (arguments != null) {
				try {
					//设置方法的可访问属性，即method.setAccessible(true)
					ReflectionUtils.makeAccessible(method);
					/*
					 * 反射回调方法，采用获取的依赖项作为参数
					 */
					method.invoke(bean, arguments);
				}
				catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			}
		}

		/**
		 * 解析指定的已缓存的方法参数，快速可获取需要注入的依赖项数组
		 *
		 * @param beanName beanName
		 * @return 需要注入的依赖项数组
		 */
		@Nullable
		private Object[] resolveCachedArguments(@Nullable String beanName) {
			//获取缓存
			Object[] cachedMethodArguments = this.cachedMethodArguments;
			//如果为null，这是第一次解析依赖返回null并且是非必须依赖的情况，这种情况直接返回null
			if (cachedMethodArguments == null) {
				return null;
			}
			//存储找到的依赖项
			Object[] arguments = new Object[cachedMethodArguments.length];
			//遍历
			for (int i = 0; i < arguments.length; i++) {
				//调用resolvedCachedArgument方法，查找每一个cachedMethodArguments的描述符，将返回的依赖项存入对应的arguments的索引位置
				arguments[i] = resolvedCachedArgument(beanName, cachedMethodArguments[i]);
			}
			return arguments;
		}

		@Nullable
		private Object[] resolveMethodArguments(Method method, Object bean, @Nullable String beanName) {
			//获取参数数量
			int argumentCount = method.getParameterCount();
			//需要注入的参数值数组
			Object[] arguments = new Object[argumentCount];
			//新建依赖描述符集合
			DependencyDescriptor[] descriptors = new DependencyDescriptor[argumentCount];
			//自动注入的beanName集合
			Set<String> autowiredBeans = new LinkedHashSet<>(argumentCount);
			Assert.state(beanFactory != null, "No BeanFactory available");
			//获取转换器
			TypeConverter typeConverter = beanFactory.getTypeConverter();
			/*遍历每一个属性，进行注入*/
			for (int i = 0; i < arguments.length; i++) {
				//新建方法参数对象
				MethodParameter methodParam = new MethodParameter(method, i);
				//新建当前参数的描述符对象，这里的required属性就是@Autowired注解的required属性值，没有就设置默认true
				DependencyDescriptor currDesc = new DependencyDescriptor(methodParam, this.required);
				currDesc.setContainingClass(bean.getClass());
				//加入到descriptors集合中
				descriptors[i] = currDesc;
				try {
					/*
					 * 调用resolveDependency方法根据类型解析依赖，返回找到的依赖，查找规则在之前讲过，注意这里的required是可以设置的
					 * 如果required设置为false，那么没找到依赖将不会抛出异常
					 * 如果找到多个依赖，那么会尝试查找最合适的依赖，就掉调用determineAutowireCandidate方法，此前就讲过了
					 * 在最后一步会尝试根据name进行匹配，如果还是不能筛选出合适的依赖，那么抛出异常
					 * 这就是byType优先于byName的原理，实际上一个resolveDependency方法就完成了
					 */
					Object arg = beanFactory.resolveDependency(currDesc, beanName, autowiredBeans, typeConverter);
					//如果返回null并且是非必须依赖
					if (arg == null && !this.required) {
						//参数值数组置为null
						arguments = null;
						break;
					}
					//否则，对应位置存入找到的依赖项
					arguments[i] = arg;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(null, beanName, new InjectionPoint(methodParam), ex);
				}
			}
			/*同步，设置缓存，类似于AutowiredFieldElement中的步骤*/
			synchronized (this) {
				//如果没有被缓存，那么尝试将解析结果加入缓存
				if (!this.cached) {
					//如果arguments数组不为null
					if (arguments != null) {
						//拷贝描述符数组
						DependencyDescriptor[] cachedMethodArguments = Arrays.copyOf(descriptors, arguments.length);
						///将每一个autowiredBeanName和beanName的依赖关系注册到dependentBeanMap和dependenciesForBeanMap缓存中
						//表示beanName的实例依赖autowiredBeanName的实例
						registerDependentBeans(beanName, autowiredBeans);
						//如果依赖bean等于参数个数，即一个参数只会注入一个依赖
						if (autowiredBeans.size() == argumentCount) {
							Iterator<String> it = autowiredBeans.iterator();
							Class<?>[] paramTypes = method.getParameterTypes();
							for (int i = 0; i < paramTypes.length; i++) {
								//获取依赖beanName
								String autowiredBeanName = it.next();
								//如果工厂中包含该bean并且类型匹配
								if (beanFactory.containsBean(autowiredBeanName) &&
										beanFactory.isTypeMatch(autowiredBeanName, paramTypes[i])) {
									/*
									 * 将当前desc描述符、beanName、字段类型存入一个ShortcutDependencyDescriptor对象中，随后赋给cachedMethodArguments
									 * 的对应索引位置，后续查找时将直接获取就有该beanName和字段类型的依赖实例返回
									 */
									cachedMethodArguments[i] = new ShortcutDependencyDescriptor(
											descriptors[i], autowiredBeanName, paramTypes[i]);
								}
							}
						}
						//将cachedMethodArguments赋值给cachedMethodArguments对象变量，方便后续查找
						this.cachedMethodArguments = cachedMethodArguments;
					}
					else {
						//如果arguments为null并且不是必须依赖，那么清空缓存
						//下一次走resolveCachedArguments方法时，直接返回null
						this.cachedMethodArguments = null;
					}
					this.cached = true;
				}
			}
			return arguments;
		}
	}


	/**
	 * DependencyDescriptor variant with a pre-resolved target bean name.
	 */
	@SuppressWarnings("serial")
	private static class ShortcutDependencyDescriptor extends DependencyDescriptor {

		private final String shortcut;

		private final Class<?> requiredType;

		public ShortcutDependencyDescriptor(DependencyDescriptor original, String shortcut, Class<?> requiredType) {
			super(original);
			this.shortcut = shortcut;
			this.requiredType = requiredType;
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			return beanFactory.getBean(this.shortcut, this.requiredType);
		}
	}


	/**
	 * {@link BeanRegistrationAotContribution} to autowire fields and methods.
	 */
	private static class AotContribution implements BeanRegistrationAotContribution {

		private static final String REGISTERED_BEAN_PARAMETER = "registeredBean";

		private static final String INSTANCE_PARAMETER = "instance";

		private final Class<?> target;

		private final Collection<AutowiredElement> autowiredElements;

		@Nullable
		private final AutowireCandidateResolver candidateResolver;

		AotContribution(Class<?> target, Collection<AutowiredElement> autowiredElements,
				@Nullable AutowireCandidateResolver candidateResolver) {

			this.target = target;
			this.autowiredElements = autowiredElements;
			this.candidateResolver = candidateResolver;
		}

		@Override
		public void applyTo(GenerationContext generationContext, BeanRegistrationCode beanRegistrationCode) {
			GeneratedClass generatedClass = generationContext.getGeneratedClasses()
					.addForFeatureComponent("Autowiring", this.target, type -> {
						type.addJavadoc("Autowiring for {@link $T}.", this.target);
						type.addModifiers(javax.lang.model.element.Modifier.PUBLIC);
					});
			GeneratedMethod generateMethod = generatedClass.getMethods().add("apply", method -> {
				method.addJavadoc("Apply the autowiring.");
				method.addModifiers(javax.lang.model.element.Modifier.PUBLIC,
						javax.lang.model.element.Modifier.STATIC);
				method.addParameter(RegisteredBean.class, REGISTERED_BEAN_PARAMETER);
				method.addParameter(this.target, INSTANCE_PARAMETER);
				method.returns(this.target);
				method.addCode(generateMethodCode(generatedClass.getName(),
						generationContext.getRuntimeHints()));
			});
			beanRegistrationCode.addInstancePostProcessor(generateMethod.toMethodReference());

			if (this.candidateResolver != null) {
				registerHints(generationContext.getRuntimeHints());
			}
		}

		private CodeBlock generateMethodCode(ClassName targetClassName, RuntimeHints hints) {
			CodeBlock.Builder code = CodeBlock.builder();
			for (AutowiredElement autowiredElement : this.autowiredElements) {
				code.addStatement(generateMethodStatementForElement(
						targetClassName, autowiredElement, hints));
			}
			code.addStatement("return $L", INSTANCE_PARAMETER);
			return code.build();
		}

		private CodeBlock generateMethodStatementForElement(ClassName targetClassName,
				AutowiredElement autowiredElement, RuntimeHints hints) {

			Member member = autowiredElement.getMember();
			boolean required = autowiredElement.required;
			if (member instanceof Field field) {
				return generateMethodStatementForField(
						targetClassName, field, required, hints);
			}
			if (member instanceof Method method) {
				return generateMethodStatementForMethod(
						targetClassName, method, required, hints);
			}
			throw new IllegalStateException(
					"Unsupported member type " + member.getClass().getName());
		}

		private CodeBlock generateMethodStatementForField(ClassName targetClassName,
				Field field, boolean required, RuntimeHints hints) {

			hints.reflection().registerField(field);
			CodeBlock resolver = CodeBlock.of("$T.$L($S)",
					AutowiredFieldValueResolver.class,
					(!required) ? "forField" : "forRequiredField", field.getName());
			AccessControl accessControl = AccessControl.forMember(field);
			if (!accessControl.isAccessibleFrom(targetClassName)) {
				return CodeBlock.of("$L.resolveAndSet($L, $L)", resolver,
						REGISTERED_BEAN_PARAMETER, INSTANCE_PARAMETER);
			}
			return CodeBlock.of("$L.$L = $L.resolve($L)", INSTANCE_PARAMETER,
					field.getName(), resolver, REGISTERED_BEAN_PARAMETER);
		}

		private CodeBlock generateMethodStatementForMethod(ClassName targetClassName,
				Method method, boolean required, RuntimeHints hints) {

			CodeBlock.Builder code = CodeBlock.builder();
			code.add("$T.$L", AutowiredMethodArgumentsResolver.class,
					(!required) ? "forMethod" : "forRequiredMethod");
			code.add("($S", method.getName());
			if (method.getParameterCount() > 0) {
				code.add(", $L", generateParameterTypesCode(method.getParameterTypes()));
			}
			code.add(")");
			AccessControl accessControl = AccessControl.forMember(method);
			if (!accessControl.isAccessibleFrom(targetClassName)) {
				hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
				code.add(".resolveAndInvoke($L, $L)", REGISTERED_BEAN_PARAMETER, INSTANCE_PARAMETER);
			}
			else {
				hints.reflection().registerMethod(method, ExecutableMode.INTROSPECT);
				CodeBlock arguments = new AutowiredArgumentsCodeGenerator(this.target,
						method).generateCode(method.getParameterTypes());
				CodeBlock injectionCode = CodeBlock.of("args -> $L.$L($L)",
						INSTANCE_PARAMETER, method.getName(), arguments);
				code.add(".resolve($L, $L)", REGISTERED_BEAN_PARAMETER, injectionCode);
			}
			return code.build();
		}

		private CodeBlock generateParameterTypesCode(Class<?>[] parameterTypes) {
			CodeBlock.Builder code = CodeBlock.builder();
			for (int i = 0; i < parameterTypes.length; i++) {
				code.add(i != 0 ? ", " : "");
				code.add("$T.class", parameterTypes[i]);
			}
			return code.build();
		}

		private void registerHints(RuntimeHints runtimeHints) {
			this.autowiredElements.forEach(autowiredElement -> {
				boolean required = autowiredElement.required;
				Member member = autowiredElement.getMember();
				if (member instanceof Field field) {
					DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(field, required);
					registerProxyIfNecessary(runtimeHints, dependencyDescriptor);
				}
				if (member instanceof Method method) {
					Class<?>[] parameterTypes = method.getParameterTypes();
					for (int i = 0; i < parameterTypes.length; i++) {
						MethodParameter methodParam = new MethodParameter(method, i);
						DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(methodParam, required);
						registerProxyIfNecessary(runtimeHints, dependencyDescriptor);
					}
				}
			});
		}

		private void registerProxyIfNecessary(RuntimeHints runtimeHints, DependencyDescriptor dependencyDescriptor) {
			if (this.candidateResolver != null) {
				Class<?> proxyType =
						this.candidateResolver.getLazyResolutionProxyClass(dependencyDescriptor, null);
				if (proxyType != null && Proxy.isProxyClass(proxyType)) {
					runtimeHints.proxies().registerJdkProxy(proxyType.getInterfaces());
				}
			}
		}

	}

}
