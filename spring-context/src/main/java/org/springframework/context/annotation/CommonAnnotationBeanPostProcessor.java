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

package org.springframework.context.annotation;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.ejb.EJB;

import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.jndi.support.SimpleJndiBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that supports common Java annotations out of the box, in particular the common
 * annotations in the {@code jakarta.annotation} package. These common Java
 * annotations are supported in many Jakarta EE technologies (e.g. JSF and JAX-RS).
 *
 * <p>This post-processor includes support for the {@link jakarta.annotation.PostConstruct}
 * and {@link jakarta.annotation.PreDestroy} annotations - as init annotation
 * and destroy annotation, respectively - through inheriting from
 * {@link InitDestroyAnnotationBeanPostProcessor} with pre-configured annotation types.
 *
 * <p>The central element is the {@link jakarta.annotation.Resource} annotation
 * for annotation-driven injection of named beans, by default from the containing
 * Spring BeanFactory, with only {@code mappedName} references resolved in JNDI.
 * The {@link #setAlwaysUseJndiLookup "alwaysUseJndiLookup" flag} enforces JNDI lookups
 * equivalent to standard Jakarta EE resource injection for {@code name} references
 * and default names as well. The target beans can be simple POJOs, with no special
 * requirements other than the type having to match.
 *
 * <p>This post-processor also supports the EJB 3 {@link jakarta.ejb.EJB} annotation,
 * analogous to {@link jakarta.annotation.Resource}, with the capability to
 * specify both a local bean name and a global JNDI name for fallback retrieval.
 * The target beans can be plain POJOs as well as EJB 3 Session Beans in this case.
 *
 * <p>For default usage, resolving resource names as Spring bean names,
 * simply define the following in your application context:
 *
 * <pre class="code">
 * &lt;bean class="org.springframework.context.annotation.CommonAnnotationBeanPostProcessor"/&gt;</pre>
 *
 * For direct JNDI access, resolving resource names as JNDI resource references
 * within the Jakarta EE application's "java:comp/env/" namespace, use the following:
 *
 * <pre class="code">
 * &lt;bean class="org.springframework.context.annotation.CommonAnnotationBeanPostProcessor"&gt;
 *   &lt;property name="alwaysUseJndiLookup" value="true"/&gt;
 * &lt;/bean&gt;</pre>
 *
 * {@code mappedName} references will always be resolved in JNDI,
 * allowing for global JNDI names (including "java:" prefix) as well. The
 * "alwaysUseJndiLookup" flag just affects {@code name} references and
 * default names (inferred from the field name / property name).
 *
 * <p><b>NOTE:</b> A default CommonAnnotationBeanPostProcessor will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom CommonAnnotationBeanPostProcessor bean definition!
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection; thus
 * the latter configuration will override the former for properties wired through
 * both approaches.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 2.5
 * @see #setAlwaysUseJndiLookup
 * @see #setResourceFactory
 * @see org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor
 * @see org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor
 */
@SuppressWarnings("serial")
public class CommonAnnotationBeanPostProcessor extends InitDestroyAnnotationBeanPostProcessor
		implements InstantiationAwareBeanPostProcessor, BeanFactoryAware, Serializable {

	// Defensive reference to JNDI API for JDK 9+ (optional java.naming module)
	private static final boolean jndiPresent = ClassUtils.isPresent(
			"javax.naming.InitialContext", CommonAnnotationBeanPostProcessor.class.getClassLoader());

	private static final Set<Class<? extends Annotation>> resourceAnnotationTypes = new LinkedHashSet<>(4);

	@Nullable
	private static final Class<? extends Annotation> ejbClass;

	static {
		resourceAnnotationTypes.add(Resource.class);

		ejbClass = loadAnnotationType("jakarta.ejb.EJB");
		if (ejbClass != null) {
			resourceAnnotationTypes.add(ejbClass);
		}
	}


	private final Set<String> ignoredResourceTypes = new HashSet<>(1);

	private boolean fallbackToDefaultTypeMatch = true;

	private boolean alwaysUseJndiLookup = false;

	@Nullable
	private transient BeanFactory jndiFactory;

	@Nullable
	private transient BeanFactory resourceFactory;

	@Nullable
	private transient BeanFactory beanFactory;

	@Nullable
	private transient StringValueResolver embeddedValueResolver;

	private final transient Map<String, InjectionMetadata> injectionMetadataCache = new ConcurrentHashMap<>(256);


	/**
	 * Create a new CommonAnnotationBeanPostProcessor,
	 * with the init and destroy annotation types set to
	 * {@link jakarta.annotation.PostConstruct} and {@link jakarta.annotation.PreDestroy},
	 * respectively.
	 * 设置父类的init 和 destroy 注解类型为javax.annotation.PostConstruct和javax.annotation.PreDestroy
	 *
	 */
	public CommonAnnotationBeanPostProcessor() {
		//设置父类InitDestroyAnnotationBeanPostProcessor的order属性
		//用于排序
		setOrder(Ordered.LOWEST_PRECEDENCE - 3);
		//设置父类InitDestroyAnnotationBeanPostProcessor的initAnnotationType属性
		//表示初始化回调注解的类型
		setInitAnnotationType(PostConstruct.class);
		//设置父类InitDestroyAnnotationBeanPostProcessor的destroyAnnotationType属性
		//表示销毁回调注解的类型
		setDestroyAnnotationType(PreDestroy.class);

		// java.naming module present on JDK 9+?
		if (jndiPresent) {
			this.jndiFactory = new SimpleJndiBeanFactory();
		}
	}


	/**
	 * Ignore the given resource type when resolving {@code @Resource} annotations.
	 * @param resourceType the resource type to ignore
	 */
	public void ignoreResourceType(String resourceType) {
		Assert.notNull(resourceType, "Ignored resource type must not be null");
		this.ignoredResourceTypes.add(resourceType);
	}

	/**
	 * Set whether to allow a fallback to a type match if no explicit name has been
	 * specified. The default name (i.e. the field name or bean property name) will
	 * still be checked first; if a bean of that name exists, it will be taken.
	 * However, if no bean of that name exists, a by-type resolution of the
	 * dependency will be attempted if this flag is "true".
	 * <p>Default is "true". Switch this flag to "false" in order to enforce a
	 * by-name lookup in all cases, throwing an exception in case of no name match.
	 * @see org.springframework.beans.factory.config.AutowireCapableBeanFactory#resolveDependency
	 */
	public void setFallbackToDefaultTypeMatch(boolean fallbackToDefaultTypeMatch) {
		this.fallbackToDefaultTypeMatch = fallbackToDefaultTypeMatch;
	}

	/**
	 * Set whether to always use JNDI lookups equivalent to standard Jakarta EE resource
	 * injection, <b>even for {@code name} attributes and default names</b>.
	 * <p>Default is "false": Resource names are used for Spring bean lookups in the
	 * containing BeanFactory; only {@code mappedName} attributes point directly
	 * into JNDI. Switch this flag to "true" for enforcing Jakarta EE style JNDI lookups
	 * in any case, even for {@code name} attributes and default names.
	 * @see #setJndiFactory
	 * @see #setResourceFactory
	 */
	public void setAlwaysUseJndiLookup(boolean alwaysUseJndiLookup) {
		this.alwaysUseJndiLookup = alwaysUseJndiLookup;
	}

	/**
	 * Specify the factory for objects to be injected into {@code @Resource} /
	 * {@code @EJB} annotated fields and setter methods,
	 * <b>for {@code mappedName} attributes that point directly into JNDI</b>.
	 * This factory will also be used if "alwaysUseJndiLookup" is set to "true" in order
	 * to enforce JNDI lookups even for {@code name} attributes and default names.
	 * <p>The default is a {@link org.springframework.jndi.support.SimpleJndiBeanFactory}
	 * for JNDI lookup behavior equivalent to standard Jakarta EE resource injection.
	 * @see #setResourceFactory
	 * @see #setAlwaysUseJndiLookup
	 */
	public void setJndiFactory(BeanFactory jndiFactory) {
		Assert.notNull(jndiFactory, "BeanFactory must not be null");
		this.jndiFactory = jndiFactory;
	}

	/**
	 * Specify the factory for objects to be injected into {@code @Resource} /
	 * {@code @EJB} annotated fields and setter methods,
	 * <b>for {@code name} attributes and default names</b>.
	 * <p>The default is the BeanFactory that this post-processor is defined in,
	 * if any, looking up resource names as Spring bean names. Specify the resource
	 * factory explicitly for programmatic usage of this post-processor.
	 * <p>Specifying Spring's {@link org.springframework.jndi.support.SimpleJndiBeanFactory}
	 * leads to JNDI lookup behavior equivalent to standard Jakarta EE resource injection,
	 * even for {@code name} attributes and default names. This is the same behavior
	 * that the "alwaysUseJndiLookup" flag enables.
	 * @see #setAlwaysUseJndiLookup
	 */
	public void setResourceFactory(BeanFactory resourceFactory) {
		Assert.notNull(resourceFactory, "BeanFactory must not be null");
		this.resourceFactory = resourceFactory;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		Assert.notNull(beanFactory, "BeanFactory must not be null");
		this.beanFactory = beanFactory;
		if (this.resourceFactory == null) {
			this.resourceFactory = beanFactory;
		}
		if (beanFactory instanceof ConfigurableBeanFactory configurableBeanFactory) {
			this.embeddedValueResolver = new EmbeddedValueResolver(configurableBeanFactory);
		}
	}


	/**
	 * 调用父类InitDestroyAnnotationBeanPostProcessor的同名方法用于处理：PostConstruct、PreDestroy注解。
	 * 随后自己处理：@WebServiceRef、@EJB、@Resource注解。
	 *
	 * @param beanDefinition      已合并的bean定义
	 * @param beanType bean的类型
	 * @param beanName beanName
	 */
	@Override
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		/*调用父类的InitDestroyAnnotationBeanPostProcessor的同名方法用于处理@PostConstruct、@PreDestroy注解*/
		super.postProcessMergedBeanDefinition(beanDefinition, beanType, beanName);
		/*自己处理@WebServiceRef、@EJB、@Resource注解*/
		InjectionMetadata metadata = findResourceMetadata(beanName, beanType, null);
		//检查配置
		metadata.checkConfigMembers(beanDefinition);
	}


	@Override
	public void resetBeanDefinition(String beanName) {
		this.injectionMetadataCache.remove(beanName);
	}

	@Override
	public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
		return null;
	}

	@Override
	public boolean postProcessAfterInstantiation(Object bean, String beanName) {
		return true;
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
		InjectionMetadata metadata = findResourceMetadata(beanName, bean.getClass(), pvs);
		try {
			metadata.inject(bean, beanName, pvs);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of resource dependencies failed", ex);
		}
		return pvs;
	}



	/**
	 *. 获取注入点ResourceMetadata元数据，具有@WebServiceRef、@EJB、@Resource注解
	 */
	private InjectionMetadata findResourceMetadata(String beanName, final Class<?> clazz, @Nullable PropertyValues pvs) {
		//获取缓存key，如果beanName为null或""，那么使用类名
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
		//尝试从缓存中获取
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
		//如果缓存为null，或者需要刷新，那么解析
		if (InjectionMetadata.needsRefresh(metadata, clazz)) {
			synchronized (this.injectionMetadataCache) {
				//加锁之后再次获取，防止并发
				metadata = this.injectionMetadataCache.get(cacheKey);
				//如果缓存为null，或者需要刷新，那么解析
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {
					if (metadata != null) {
						metadata.clear(pvs);
					}
					//创建该类型的ResourceMetadata
					metadata = buildResourceMetadata(clazz);
					//存入缓存
					this.injectionMetadataCache.put(cacheKey, metadata);
				}
			}
		}
		return metadata;
	}

	/**
	 * 创建ResourceMetadata，实际类型为InjectionMetadata
	 * @param clazz bean的类型
	 * @return InjectionMetadata对象，内部持有注入点注解标注的注入点
	 */
	private InjectionMetadata buildResourceMetadata(Class<?> clazz) {
		//确定给定类是否是承载指定注释的候选项（在类型、方法或字段级别）。
		//如果任何一个注解的全路径名都不是以"java."开始，并且该Class全路径名以"start."开始，或者Class的类型为Ordered.class，那么返回false，否则其他情况都返回true
		if (!AnnotationUtils.isCandidateClass(clazz, resourceAnnotationTypes)) {
			//如果不满足，那么直接返回空InjectionMetadata对象
			return InjectionMetadata.EMPTY;
		}

		//存储资源注入点的InjectedElement对象集合
		List<InjectionMetadata.InjectedElement> elements = new ArrayList<>();
		//目标类型
		Class<?> targetClass = clazz;
		/*循环遍历该类及其父类，直到父类为Object或者null*/
		do {
			//当前Class的资源注入点的InjectedElement对象集合
			final List<InjectionMetadata.InjectedElement> currElements = new ArrayList<>();
			/*
			 * 1 循环过滤所有的字段，查找被资源注解（@WebServiceRef、@EJB、@Resource）标注的字段注入点
			 */
			ReflectionUtils.doWithLocalFields(targetClass, field -> {
				/*如果ejbClass不为null，并且该方法上存在@EJB的注解*/
				if (ejbClass != null && field.isAnnotationPresent(ejbClass)) {
					//如果字段是静态的，抛出异常
					if (Modifier.isStatic(field.getModifiers())) {
						throw new IllegalStateException("@EJB annotation is not supported on static fields");
					}
					//那么根据当前字段新建一个EjbRefElement，添加到currElements中
					//EjbRefElement表示了一个具有@EJB注解的字段
					currElements.add(new EjbRefElement(field, field, null));
				}
				/*否则，如果该字段上存在@Resource的注解*/
				else if (field.isAnnotationPresent(Resource.class)) {
					//如果字段是静态的，抛出异常
					if (Modifier.isStatic(field.getModifiers())) {
						throw new IllegalStateException("@Resource annotation is not supported on static fields");
					}
					//如果当前字段的类型的名字不在ignoredResourceTypes中，那么根据当前字段新建一个ResourceElement，添加到currElements中
					//EjbRefElement表示了一个具有@Resource注解且不被忽略的字段
					//这里可以知道，如果不想注入某一类型对象 可以通过ignoreResourceType方法将其加入ignoredResourceTypes中
					// 默认加入了"javax.xml.ws.WebServiceContext"，该类型字段需要运行时注入
					if (!this.ignoredResourceTypes.contains(field.getType().getName())) {
						currElements.add(new ResourceElement(field, field, null));
					}
				}
			});

			/*
			 * 2 循环过滤所有的方法（不包括构造器），查找被资源注解（@WebServiceRef、@EJB、@Resource）标注的方法
			 * 这几个注解一般都是标注在字段、方法、类上的，构造器上没有标注
			 */
			ReflectionUtils.doWithLocalMethods(targetClass, method -> {
				//查找原始方法而非编译器为我们生成的方法
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				if (!BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					return;
				}
				//如果当前方法重写了父类的方法，则使用子类的
				if (method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					/*如果ejbClass不为null，并且该方法上存在@EJB的注解*/
					if (ejbClass != null && bridgedMethod.isAnnotationPresent(ejbClass)) {
						//如果方法是静态的，抛出异常
						if (Modifier.isStatic(method.getModifiers())) {
							throw new IllegalStateException("@EJB annotation is not supported on static methods");
						}
						//如果方法参数不是一个，抛出异常
						if (method.getParameterCount() != 1) {
							throw new IllegalStateException("@EJB annotation requires a single-arg method: " + method);
						}
						//查找方法参数
						PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
						//那么根据当前方法和参数新建一个EjbRefElement，添加到currElements中
						//EjbRefElement表示了一个具有@EJB注解的方法
						currElements.add(new EjbRefElement(method, bridgedMethod, pd));
					}
					/*否则，如果该字段上存在@Resource的注解*/
					else if (bridgedMethod.isAnnotationPresent(Resource.class)) {
						//如果字段是静态的，抛出异常
						if (Modifier.isStatic(method.getModifiers())) {
							throw new IllegalStateException("@Resource annotation is not supported on static methods");
						}
						Class<?>[] paramTypes = method.getParameterTypes();
						if (paramTypes.length != 1) {
							throw new IllegalStateException("@Resource annotation requires a single-arg method: " + method);
						}
						//如果当前字段的类型的名字不在ignoredResourceTypes中，那么根据当前字段新建一个ResourceElement，添加到currElements中
						//EjbRefElement表示了一个具有@Resource注解且不被忽略的字段
						//这里可以知道，如果不想注入某一类型对象 可以通过ignoreResourceType方法将其加入ignoredResourceTypes中
						// 默认加入了"javax.xml.ws.WebServiceContext"，该类型字段需要运行时注入
						if (!this.ignoredResourceTypes.contains(paramTypes[0].getName())) {
							PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
							currElements.add(new ResourceElement(method, bridgedMethod, pd));
						}
					}
				}
			});
			//currElements集合整体添加到elements集合的开头，即父类的资源注解在前面
			elements.addAll(0, currElements);
			//获取下一个目标类型，是当前类型的父类型
			targetClass = targetClass.getSuperclass();
		}
		//如果目标类型不为null并且不是Object.class类型，那么继续循环，否则结束循环
		while (targetClass != null && targetClass != Object.class);
		//根据找到的elements和Class创建InjectionMetadata对象，如果没有任何注入点元素，那么返回一个空的InjectionMetadata
		return InjectionMetadata.forElements(elements, clazz);
	}

	/**
	 * Obtain a lazily resolving resource proxy for the given name and type,
	 * delegating to {@link #getResource} on demand once a method call comes in.
	 * @param element the descriptor for the annotated field/method
	 * @param requestingBeanName the name of the requesting bean
	 * @return the resource object (never {@code null})
	 * @since 4.2
	 * @see #getResource
	 * @see Lazy
	 */
	protected Object buildLazyResourceProxy(final LookupElement element, final @Nullable String requestingBeanName) {
		TargetSource ts = new TargetSource() {
			@Override
			public Class<?> getTargetClass() {
				return element.lookupType;
			}
			@Override
			public boolean isStatic() {
				return false;
			}
			@Override
			public Object getTarget() {
				return getResource(element, requestingBeanName);
			}
			@Override
			public void releaseTarget(Object target) {
			}
		};

		ProxyFactory pf = new ProxyFactory();
		pf.setTargetSource(ts);
		if (element.lookupType.isInterface()) {
			pf.addInterface(element.lookupType);
		}
		ClassLoader classLoader = (this.beanFactory instanceof ConfigurableBeanFactory configurableBeanFactory ?
				configurableBeanFactory.getBeanClassLoader() : null);
		return pf.getProxy(classLoader);
	}

	/**
	 * Obtain the resource object for the given name and type.
	 * @param element the descriptor for the annotated field/method
	 * @param requestingBeanName the name of the requesting bean
	 * @return the resource object (never {@code null})
	 * @throws NoSuchBeanDefinitionException if no corresponding target resource found
	 */
	protected Object getResource(LookupElement element, @Nullable String requestingBeanName)
			throws NoSuchBeanDefinitionException {

		// JNDI lookup to perform?
		String jndiName = null;
		if (StringUtils.hasLength(element.mappedName)) {
			jndiName = element.mappedName;
		}
		else if (this.alwaysUseJndiLookup) {
			jndiName = element.name;
		}
		if (jndiName != null) {
			if (this.jndiFactory == null) {
				throw new NoSuchBeanDefinitionException(element.lookupType,
						"No JNDI factory configured - specify the 'jndiFactory' property");
			}
			return this.jndiFactory.getBean(jndiName, element.lookupType);
		}

		// Regular resource autowiring
		if (this.resourceFactory == null) {
			throw new NoSuchBeanDefinitionException(element.lookupType,
					"No resource factory configured - specify the 'resourceFactory' property");
		}
		return autowireResource(this.resourceFactory, element, requestingBeanName);
	}

	/**
	 * Obtain a resource object for the given name and type through autowiring
	 * based on the given factory.
	 * 通过基于给定工厂的获取给定名称和类型的资源对象。
	 * @param factory the factory to autowire against
	 * @param element the descriptor for the annotated field/method
	 * @param requestingBeanName the name of the requesting bean
	 * @return the resource object (never {@code null})
	 * @throws NoSuchBeanDefinitionException if no corresponding target resource found
	 */
	protected Object autowireResource(BeanFactory factory, LookupElement element, @Nullable String requestingBeanName)
			throws NoSuchBeanDefinitionException {

		Object resource;
		//自动注入的beanName集合
		Set<String> autowiredBeanNames;
		/*
		 * 获取属性名，此前创建ResourceElement对象时就已处理过了
		 */
		String name = element.name;
		//DefaultListableBeanFactory属于AutowireCapableBeanFactory类型
		if (factory instanceof AutowireCapableBeanFactory beanFactory) {
			//获取依赖描述符（可能是字段或者方法），实际类型为LookupDependencyDescriptor，它的required属性为true
			DependencyDescriptor descriptor = element.getDependencyDescriptor();
			/*
			 * 如果fallbackToDefaultTypeMatch属性为true，默认就是true，表示如果未指定名称是否退回到根据type查找
			 * 并且，如果isDefaultName属性为true，这表示没有设置@Resource注解的name属性，将会根据type查找
			 * 并且，如果此 Bean 工厂及其父工厂不包含具有给定名称的 bean 定义或bean实例，将会根据type查找
			 *
			 * 这三个条件都满足，那么根据type查找
			 */
			if (this.fallbackToDefaultTypeMatch && element.isDefaultName && !factory.containsBean(name)) {
				autowiredBeanNames = new LinkedHashSet<>();
				//调用resolveDependency方法根据类型解析依赖，返回找到的依赖，查找规则在之前讲过，注意这里的required=true
				resource = beanFactory.resolveDependency(descriptor, requestingBeanName, autowiredBeanNames, null);
				//如果返回null，表示没找到依赖项，那么抛出异常
				if (resource == null) {
					throw new NoSuchBeanDefinitionException(element.getLookupType(), "No resolvable resource object");
				}
			}
			/*
			 * 否则，如果fallbackToDefaultTypeMatch为false，或者设置了name属性，或者存在该name的bean或者，那么根据name查找
			 * 这三个条件满足一个，那么根据name查找
			 */
			else {
				//根据name查找，如果设置了type属性，那么还必须比配设置的type属性
				//内部实际上就是调用的getBean方法
				resource = beanFactory.resolveBeanByName(name, descriptor);
				//返回一个单个元素的集合
				autowiredBeanNames = Collections.singleton(name);
			}
		} else {
			resource = factory.getBean(name, element.lookupType);
			autowiredBeanNames = Collections.singleton(name);
		}

		if (factory instanceof ConfigurableBeanFactory beanFactory) {
			/*遍历自动注入的beanName，这些bean也算作当前bean定义依赖的bean*/
			for (String autowiredBeanName : autowiredBeanNames) {
				if (requestingBeanName != null && beanFactory.containsBean(autowiredBeanName)) {
					//那么将autowiredBeanName和requestingBeanName的依赖关系注册到dependentBeanMap和dependenciesForBeanMap缓存中
					//这个方法我们在前面就讲过了
					beanFactory.registerDependentBean(autowiredBeanName, requestingBeanName);
				}
			}
		}
		//返回resource，即找到的依赖对象
		return resource;
	}



	@SuppressWarnings("unchecked")
	@Nullable
	private static Class<? extends Annotation> loadAnnotationType(String name) {
		try {
			return (Class<? extends Annotation>)
					ClassUtils.forName(name, CommonAnnotationBeanPostProcessor.class.getClassLoader());
		}
		catch (ClassNotFoundException ex) {
			return null;
		}
	}


	/**
	 * Class representing generic injection information about an annotated field
	 * or setter method, supporting @Resource and related annotations.
	 */
	protected abstract static class LookupElement extends InjectionMetadata.InjectedElement {

		/**
		 * 依赖name
		 */
		protected String name = "";

		/**
		 * 是否没有设置name属性
		 */
		protected boolean isDefaultName = false;

		/**
		 * 依赖类型
		 */
		protected Class<?> lookupType = Object.class;

		@Nullable
		protected String mappedName;

		public LookupElement(Member member, @Nullable PropertyDescriptor pd) {
			super(member, pd);
		}

		/**
		 * Return the resource name for the lookup.
		 */
		public final String getName() {
			return this.name;
		}

		/**
		 * Return the desired type for the lookup.
		 */
		public final Class<?> getLookupType() {
			return this.lookupType;
		}

		/**
		 * Build a DependencyDescriptor for the underlying field/method.
		 * 为基础字段/方法生成依赖性描述符，使用了member和lookupType
		 * lookupType首先取指定的type属性指定的类型，没有则取属性或者参数的类型
		 * LookupDependencyDescriptor的required属性为true
		 */
		public final DependencyDescriptor getDependencyDescriptor() {
			if (this.isField) {
				return new LookupDependencyDescriptor((Field) this.member, this.lookupType);
			}
			else {
				return new LookupDependencyDescriptor((Method) this.member, this.lookupType);
			}
		}
	}


	/**
	 * Class representing injection information about an annotated field
	 * or setter method, supporting the @Resource annotation.
	 * 表示注解字段或 setter 方法的注入信息的类，支持@Resource注解。
	 */
	private class ResourceElement extends LookupElement {

		/**
		 * 是否需要返回代理依赖对象
		 */
		private final boolean lazyLookup;

		/**
		 * 构造器，在applyMergedBeanDefinitionPostProcessors方法中被调用，用来创建@Resource注解相关对象
		 *
		 * @param member 字段或者方法
		 * @param ae     字段或者方法
		 * @param pd     构造器传递null
		 */
		public ResourceElement(Member member, AnnotatedElement ae, @Nullable PropertyDescriptor pd) {
			//调用父类LookupElement的构造器
			super(member, pd);
			//获取@LookupElement注解
			Resource resource = ae.getAnnotation(Resource.class);
			//获取name属性值，如果没设置，那么默认返回""
			String resourceName = resource.name();
			//获取type属性，如果没设置，那么默认返回Object.class
			Class<?> resourceType = resource.type();
			//是否没有设置name属性，如果没设置，那么isDefaultName=true
			this.isDefaultName = !StringUtils.hasLength(resourceName);
			/*如果没设置了name属性，使用自己的规则作为name*/
			if (this.isDefaultName) {
				//获取属性名或者方法名
				resourceName = this.member.getName();
				//如果是setter方法，并且长度大于3个字符
				if (this.member instanceof Method && resourceName.startsWith("set") && resourceName.length() > 3) {
					//那么截取setter方法名的"set"之后的部分，并进行处理：如果至少开头两个字符是大写，那么就返回原截取的值，否则返回开头为小写的截取的值
					resourceName = Introspector.decapitalize(resourceName.substring(3));
				}
			}
			/*如果设置了name属性，那么解析name属性值*/
			else if (embeddedValueResolver != null) {
				//这里就是解析name值中的占位符的逻辑，将占位符替换为属性值
				//因此设置的name属性支持占位符，即${.. : ..}，占位符的语法和解析之前就学过了，这里的占位符支持普通方式从外部配置文件中加载进来的属性以及environment的属性
				resourceName = embeddedValueResolver.resolveStringValue(resourceName);
			}
			/*如果type不是Object类型，那么检查指定的type*/
			if (Object.class != resourceType) {
				//如果指定type和字段类型或者方法参数类型不兼容，那么抛出异常
				checkResourceType(resourceType);
			} else {
				//如果是，那么获取字段类型或者方法参数类型作为type
				resourceType = getResourceType();
			}
			//设置为name属性为resourceName
			this.name = (resourceName != null ? resourceName : "");
			//设置lookupType属性为resourceType
			this.lookupType = resourceType;
			//获取注解上的lookup属性值，如果没设置，那么默认返回""，一般没人设置
			String lookupValue = resource.lookup();
			//如果没有设置，那么设置mappedName属性值为mappedName属性值，否则设置为lookupValue的值
			this.mappedName = (StringUtils.hasLength(lookupValue) ? lookupValue : resource.mappedName());
			//尝试获取@Lazy注解
			Lazy lazy = ae.getAnnotation(Lazy.class);
			//如果存在@Lazy注解并且值为true，那么lazyLookup为true，否则就是false，这表示是否返回一个代理的依赖
			this.lazyLookup = (lazy != null && lazy.value());
		}

		@Override
		protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
			//一般都是走getResource的逻辑，如果有@Lazy注解，则走buildLazyResourceProxy的逻辑，创建一个代理对象返回
			return (this.lazyLookup ? buildLazyResourceProxy(this, requestingBeanName) :
					getResource(this, requestingBeanName));
		}
	}


	/**
	 * Class representing injection information about an annotated field
	 * or setter method, supporting the @EJB annotation.
	 */
	private class EjbRefElement extends LookupElement {

		private final String beanName;

		public EjbRefElement(Member member, AnnotatedElement ae, @Nullable PropertyDescriptor pd) {
			super(member, pd);
			EJB resource = ae.getAnnotation(EJB.class);
			String resourceBeanName = resource.beanName();
			String resourceName = resource.name();
			this.isDefaultName = !StringUtils.hasLength(resourceName);
			if (this.isDefaultName) {
				resourceName = this.member.getName();
				if (this.member instanceof Method && resourceName.startsWith("set") && resourceName.length() > 3) {
					resourceName = StringUtils.uncapitalizeAsProperty(resourceName.substring(3));
				}
			}
			Class<?> resourceType = resource.beanInterface();
			if (Object.class != resourceType) {
				checkResourceType(resourceType);
			}
			else {
				// No resource type specified... check field/method.
				resourceType = getResourceType();
			}
			this.beanName = resourceBeanName;
			this.name = resourceName;
			this.lookupType = resourceType;
			this.mappedName = resource.mappedName();
		}

		@Override
		protected Object getResourceToInject(Object target, @Nullable String requestingBeanName) {
			if (StringUtils.hasLength(this.beanName)) {
				if (beanFactory != null && beanFactory.containsBean(this.beanName)) {
					// Local match found for explicitly specified local bean name.
					Object bean = beanFactory.getBean(this.beanName, this.lookupType);
					if (requestingBeanName != null && beanFactory instanceof ConfigurableBeanFactory configurableBeanFactory) {
						configurableBeanFactory.registerDependentBean(this.beanName, requestingBeanName);
					}
					return bean;
				}
				else if (this.isDefaultName && !StringUtils.hasLength(this.mappedName)) {
					throw new NoSuchBeanDefinitionException(this.beanName,
							"Cannot resolve 'beanName' in local BeanFactory. Consider specifying a general 'name' value instead.");
				}
			}
			// JNDI name lookup - may still go to a local BeanFactory.
			return getResource(this, requestingBeanName);
		}
	}


	/**
	 * Extension of the DependencyDescriptor class,
	 * overriding the dependency type with the specified resource type.
	 */
	private static class LookupDependencyDescriptor extends DependencyDescriptor {

		private final Class<?> lookupType;

		public LookupDependencyDescriptor(Field field, Class<?> lookupType) {
			super(field, true);
			this.lookupType = lookupType;
		}

		public LookupDependencyDescriptor(Method method, Class<?> lookupType) {
			super(new MethodParameter(method, 0), true);
			this.lookupType = lookupType;
		}

		@Override
		public Class<?> getDependencyType() {
			return this.lookupType;
		}
	}

}
