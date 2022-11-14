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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.groovy.GroovyBeanDefinitionReader;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Reads a given fully-populated set of ConfigurationClass instances, registering bean
 * definitions with the given {@link BeanDefinitionRegistry} based on its contents.
 *
 * <p>This class was modeled after the {@link BeanDefinitionReader} hierarchy, but does
 * not implement/extend any of its artifacts as a set of configuration classes is not a
 * {@link Resource}.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Sebastien Deleuze
 * @since 3.0
 * @see ConfigurationClassParser
 */
class ConfigurationClassBeanDefinitionReader {

	private static final Log logger = LogFactory.getLog(ConfigurationClassBeanDefinitionReader.class);

	private static final ScopeMetadataResolver scopeMetadataResolver = new AnnotationScopeMetadataResolver();

	private final BeanDefinitionRegistry registry;

	private final SourceExtractor sourceExtractor;

	private final ResourceLoader resourceLoader;

	private final Environment environment;

	private final BeanNameGenerator importBeanNameGenerator;

	private final ImportRegistry importRegistry;

	private final ConditionEvaluator conditionEvaluator;


	/**
	 * Create a new {@link ConfigurationClassBeanDefinitionReader} instance
	 * that will be used to populate the given {@link BeanDefinitionRegistry}.
	 */
	ConfigurationClassBeanDefinitionReader(BeanDefinitionRegistry registry, SourceExtractor sourceExtractor,
			ResourceLoader resourceLoader, Environment environment, BeanNameGenerator importBeanNameGenerator,
			ImportRegistry importRegistry) {

		this.registry = registry;
		this.sourceExtractor = sourceExtractor;
		this.resourceLoader = resourceLoader;
		this.environment = environment;
		this.importBeanNameGenerator = importBeanNameGenerator;
		this.importRegistry = importRegistry;
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	/**
	 * Read {@code configurationModel}, registering bean definitions
	 * with the registry based on its contents.
	 * 向注册表注册 bean 定义
	 */
	public void loadBeanDefinitions(Set<ConfigurationClass> configurationModel) {
		//评估@Conditional注释，判断当前配置类的解析否需要跳过，将会跟踪结果并考虑到"importBy"。
		TrackedConditionEvaluator trackedConditionEvaluator = new TrackedConditionEvaluator();
		// 遍历配置类进行解析
		for (ConfigurationClass configClass : configurationModel) {
			//从ConfigurationClass加载bean定义
			loadBeanDefinitionsForConfigurationClass(configClass, trackedConditionEvaluator);
		}
	}

	/**
	 * Read a particular {@link ConfigurationClass}, registering bean definitions
	 * for the class itself and all of its {@link Bean} methods.
	 * 读取特定的ConfigurationClass配置类，注册该类本身及其所有@Bean方法的 bean 定义
	 */
	private void loadBeanDefinitionsForConfigurationClass(
			ConfigurationClass configClass, TrackedConditionEvaluator trackedConditionEvaluator) {

		//如果trackedConditionEvaluator的shouldSkip方法返回true，即应该跳过
		if (trackedConditionEvaluator.shouldSkip(configClass)) {
			//获取beanName
			String beanName = configClass.getBeanName();
			//如果存在beanName，并且注册表中已经包含了该beanName的bean定义
			if (StringUtils.hasLength(beanName) && this.registry.containsBeanDefinition(beanName)) {
				/*
				 * 从注册表中移除该beanName的bean定义
				 * 因此，此前加入进来的配置类的bean定义将可能被移除
				 */
				this.registry.removeBeanDefinition(beanName);
			}
			//移除importRegistry即importStack中的缓存
			this.importRegistry.removeImportingClass(configClass.getMetadata().getClassName());
			return;
		}

		// 判断配置类是否由其他配置类导入的，而不是@Import注解导入的
		if (configClass.isImported()) {
			// 将这个配置类本身解析成一个beanDefinition
			registerBeanDefinitionForImportedConfigurationClass(configClass);
		}
		// 判断这个配置类是不是有@Bean注解的方法，如果有，则将@Bean注解返回的结果解析成一个BeanDefinition
		for (BeanMethod beanMethod : configClass.getBeanMethods()) {
			loadBeanDefinitionsForBeanMethod(beanMethod);
		}
		// 获取这个配置类中@ImportResources或者@ImportResource导入的类，这些类解析成一个BeanDefinition
		loadBeanDefinitionsFromImportedResources(configClass.getImportedResources());
		// 将前面@Import注解导入的实现了ImportBeanDefinitionRegistrar的接口的类解析成一个BeanDefinition
		loadBeanDefinitionsFromRegistrars(configClass.getImportBeanDefinitionRegistrars());
	}

	/**
	 * Register the {@link Configuration} class itself as a bean definition.
	 * 将配置类本身注册为 bean 定义，这里就是对非静态内部配置类进行注册的地方
	 */
	private void registerBeanDefinitionForImportedConfigurationClass(ConfigurationClass configClass) {
		AnnotationMetadata metadata = configClass.getMetadata();
		//创建AnnotatedGenericBeanDefinition类型的bean定义
		AnnotatedGenericBeanDefinition configBeanDef = new AnnotatedGenericBeanDefinition(metadata);

		ScopeMetadata scopeMetadata = scopeMetadataResolver.resolveScopeMetadata(configBeanDef);
		configBeanDef.setScope(scopeMetadata.getScopeName());
		//查找或者生成beanName，采用的生成器是FullyQualifiedAnnotationBeanNameGenerator
		//它继承了AnnotationBeanNameGenerator，区别就在于如果没指定beanName那么自己的beanName生成规则是直接以全路径类名作为beanName
		String configBeanName = this.importBeanNameGenerator.generateBeanName(configBeanDef, this.registry);
		//处理类上的其他通用注解：@Lazy, @Primary, @DependsOn, @Role, @Description
		AnnotationConfigUtils.processCommonDefinitionAnnotations(configBeanDef, metadata);
		//封装成为BeanDefinitionHolder对象
		BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(configBeanDef, configBeanName);
		//根据proxyMode属性的值，判断是否需要创建scope代理，一般都是不需要的
		definitionHolder = AnnotationConfigUtils.applyScopedProxyMode(scopeMetadata, definitionHolder, this.registry);
		//调用registerBeanDefinition方法注册BeanDefinition到注册表的缓存中，该方法此前已经讲过了
		this.registry.registerBeanDefinition(definitionHolder.getBeanName(), definitionHolder.getBeanDefinition());
		//设置beanName
		configClass.setBeanName(configBeanName);

		if (logger.isTraceEnabled()) {
			logger.trace("Registered bean definition for imported class '" + configBeanName + "'");
		}
	}

	/**
	 * Read the given {@link BeanMethod}, registering bean definitions
	 * with the BeanDefinitionRegistry based on its contents.
	 * 根据给定的BeanMethod解析为bean定义像注册表中注册
	 */
	private void loadBeanDefinitionsForBeanMethod(BeanMethod beanMethod) {
		//获取方法所属的类
		ConfigurationClass configClass = beanMethod.getConfigurationClass();
		MethodMetadata metadata = beanMethod.getMetadata();
		//获取方法名
		String methodName = metadata.getMethodName();

		// Do we need to mark the bean as skipped by its condition?
		/*
		 * 处理方法上的@Conditional注解，判断是否应该跳过此方法的处理
		 * 这里的metadata就是方法元数据，MethodMetadata
		 */
		//如果shouldSkip返回true，即当前@Bean的方法应该跳过解析，这里的phase生效的阶段参数为REGISTER_BEAN
		if (this.conditionEvaluator.shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN)) {
			//那么加入到当前配置类的skippedBeanMethods缓存中
			configClass.skippedBeanMethods.add(methodName);
			return;
		}
		//如果此前就解析了该方法，并且应该跳过，那么直接返回
		if (configClass.skippedBeanMethods.contains(methodName)) {
			return;
		}

		//获取@Bean注解的属性集合
		AnnotationAttributes bean = AnnotationConfigUtils.attributesFor(metadata, Bean.class);
		Assert.state(bean != null, "No @Bean annotation attributes");

		// Consider name and any aliases
		//获取name属性集合
		List<String> names = new ArrayList<>(Arrays.asList(bean.getStringArray("name")));
		/*
		 * 获取beanName。如果设置了name属性，那么将第一个值作为beanName，其他的值作为别名，否则直接将方法名作为beanName
		 */
		String beanName = (!names.isEmpty() ? names.remove(0) : methodName);

		// Register aliases even when overridden
		for (String alias : names) {
			/*
			 * 注册别名映射，registerAlias方法我们在此前"IoC容器初始化(3)"的文章中已经讲过了
			 * 将是将别名alias和名字beanName的映射注册到SimpleAliasRegistry注册表的aliasMap缓存汇总
			 */
			this.registry.registerAlias(beanName, alias);
		}

		// Has this effectively been overridden before (e.g. via XML)?
		/*
		 * 校验是否存在同名的bean定义，以及是否允许同名的bean定义覆盖
		 */
		if (isOverriddenByExistingDefinition(beanMethod, beanName)) {
			//如果返回true，并且如果beanName就等于当前bean方法所属的类的beanName，那么抛出异常
			if (beanName.equals(beanMethod.getConfigurationClass().getBeanName())) {
				throw new BeanDefinitionStoreException(beanMethod.getConfigurationClass().getResource().getDescription(),
						beanName, "Bean name derived from @Bean method '" + beanMethod.getMetadata().getMethodName() +
						"' clashes with bean name for containing configuration class; please make those names unique!");
			}
			//如果返回true，直接返回，当前bean方法不再解析
			return;
		}
		//新建一个ConfigurationClassBeanDefinition类型的bean定义，从这里可知@Bean方法的bean定义的类型
		ConfigurationClassBeanDefinition beanDef = new ConfigurationClassBeanDefinition(configClass, metadata, beanName);
		beanDef.setSource(this.sourceExtractor.extractSource(metadata, configClass.getResource()));
		//如果当前bean方法是静态的
		if (metadata.isStatic()) {
			// static @Bean method
			if (configClass.getMetadata() instanceof StandardAnnotationMetadata sam) {
				beanDef.setBeanClass(sam.getIntrospectedClass());
			}
			else {
				beanDef.setBeanClassName(configClass.getMetadata().getClassName());
			}
			//设置工厂方法名
			beanDef.setUniqueFactoryMethodName(methodName);
		}
		else {
			// instance @Bean method
			beanDef.setFactoryBeanName(configClass.getBeanName());
			beanDef.setUniqueFactoryMethodName(methodName);
		}

		//设置解析的工厂方法
		if (metadata instanceof StandardMethodMetadata sam) {
			beanDef.setResolvedFactoryMethod(sam.getIntrospectedMethod());
		}

		//设置自动装配模式为构造器自动注入
		beanDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
		//处理方法上的其他通用注解：@Lazy, @Primary, @DependsOn, @Role, @Description
		AnnotationConfigUtils.processCommonDefinitionAnnotations(beanDef, metadata);

		//设置autowireCandidate属性，默认tue
		boolean autowireCandidate = bean.getBoolean("autowireCandidate");
		if (!autowireCandidate) {
			beanDef.setAutowireCandidate(false);
		}

		//设置initMethodName属性
		String initMethodName = bean.getString("initMethod");
		if (StringUtils.hasText(initMethodName)) {
			beanDef.setInitMethodName(initMethodName);
		}
		//设置destroyMethod属性
		String destroyMethodName = bean.getString("destroyMethod");
		beanDef.setDestroyMethodName(destroyMethodName);

		// Consider scoping
		//考虑作用域和代理
		ScopedProxyMode proxyMode = ScopedProxyMode.NO;
		AnnotationAttributes attributes = AnnotationConfigUtils.attributesFor(metadata, Scope.class);
		if (attributes != null) {
			//设置作用域
			beanDef.setScope(attributes.getString("value"));
			//获取作用域代理属性，默认不使用代理
			proxyMode = attributes.getEnum("proxyMode");
			if (proxyMode == ScopedProxyMode.DEFAULT) {
				proxyMode = ScopedProxyMode.NO;
			}
		}

		// Replace the original bean definition with the target one, if necessary
		//如有必要，将原始 bean 定义替换为代理目标定义
		BeanDefinition beanDefToRegister = beanDef;
		if (proxyMode != ScopedProxyMode.NO) {
			BeanDefinitionHolder proxyDef = ScopedProxyCreator.createScopedProxy(
					new BeanDefinitionHolder(beanDef, beanName), this.registry,
					proxyMode == ScopedProxyMode.TARGET_CLASS);
			beanDefToRegister = new ConfigurationClassBeanDefinition(
					(RootBeanDefinition) proxyDef.getBeanDefinition(), configClass, metadata, beanName);
		}

		if (logger.isTraceEnabled()) {
			logger.trace(String.format("Registering bean definition for @Bean method %s.%s()",
					configClass.getMetadata().getClassName(), beanName));
		}
		//调用registerBeanDefinition方法注册BeanDefinition到注册表的缓存中
		this.registry.registerBeanDefinition(beanName, beanDefToRegister);
	}

	/**
	 * 是否已存在同名bean定义或者允许现有的bean定义被覆盖
	 */
	protected boolean isOverriddenByExistingDefinition(BeanMethod beanMethod, String beanName) {
		//如果注册表中不包含该beanName的bean定义
		if (!this.registry.containsBeanDefinition(beanName)) {
			return false;
		}
		//表示注册表中包含该beanName的bean定义
		BeanDefinition existingBeanDef = this.registry.getBeanDefinition(beanName);

		// Is the existing bean definition one that was created from a configuration class?
		// -> allow the current bean method to override, since both are at second-pass level.
		// However, if the bean method is an overloaded case on the same configuration class,
		// preserve the existing bean definition.
		/*
		 * 如果现有的 bean 定义也是从通过bean方法创建的，那么允许bean 方法重写
		 */
		if (existingBeanDef instanceof ConfigurationClassBeanDefinition ccbd) {
			//如果这两个bean方法属于同一个类
			if (ccbd.getMetadata().getClassName().equals(
					beanMethod.getConfigurationClass().getMetadata().getClassName())) {
				//如果这两个bean方法的方法名一致，即重载方法情况，那么返回true，保留现有的 bean 定义
				if (ccbd.getFactoryMethodMetadata().getMethodName().equals(ccbd.getFactoryMethodName())) {
					ccbd.setNonUniqueFactoryMethodName(ccbd.getFactoryMethodMetadata().getMethodName());
				}
				return true;
			}
			//否则，返回false，因为这两个bean方法不属于同一个类，当前bean方法将覆盖这个同名bean定义
			else {
				return false;
			}
		}

		// A bean definition resulting from a component scan can be silently overridden
		// by an @Bean method, as of 4.2...
		/*
		 * 从Spring 4.2开始，由组件扫描产生的bean定义可以被@Bean方法静默地覆盖
		 * 如果该同名bean定义是通过组件扫描注解产生的（通过组件扫描主角儿产生的bean定义类型就是ScannedGenericBeanDefinition）
		 * 那么返回false，当前bean方法将覆盖这个同名bean定义
		 */
		if (existingBeanDef instanceof ScannedGenericBeanDefinition) {
			return false;
		}

		// Has the existing bean definition bean marked as a framework-generated bean?
		// -> allow the current bean method to override it, since it is application-level
		/*
		 * 现有 bean 定义的role已标记为框架生成的 bean 吗？如果是，这允许当前 bean 方法重写它，因为它是应用程序级
		 * 那么返回false，当前bean方法将覆盖这个同名bean定义
		 */
		if (existingBeanDef.getRole() > BeanDefinition.ROLE_APPLICATION) {
			return false;
		}

		// At this point, it's a top-level override (probably XML), just having been parsed
		// before configuration class processing kicks in...
		/*
		 * 如果现有 bean 定义是通过XML产生的，并且不允许同名的BeanDefinition 覆盖（默认允许）
		 * 那么因为存在同名的bean定义而抛出异常"@Bean definition illegally overridden by existing bean definition: "
		 */
		if (this.registry instanceof DefaultListableBeanFactory dlbf &&
				!dlbf.isAllowBeanDefinitionOverriding()) {
			throw new BeanDefinitionStoreException(beanMethod.getConfigurationClass().getResource().getDescription(),
					beanName, "@Bean definition illegally overridden by existing bean definition: " + existingBeanDef);
		}
		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Skipping bean definition for %s: a definition for bean '%s' " +
					"already exists. This top-level bean definition is considered as an override.",
					beanMethod, beanName));
		}
		return true;
	}

	/**
	 * 加载、解析@ImportedResource注解引入的XML配置文件中的bean定义到注册表中。
	 * @param importedResources 解析后的资源路径字符串
	 */
	private void loadBeanDefinitionsFromImportedResources(
			Map<String, Class<? extends BeanDefinitionReader>> importedResources) {

		//bean定义读取器的缓存
		Map<Class<?>, BeanDefinitionReader> readerInstanceCache = new HashMap<>();

		/*循环加载*/
		importedResources.forEach((resource, readerClass) -> {
			// Default reader selection necessary?
			if (BeanDefinitionReader.class == readerClass) {
				//支持 Groovy 语言
				if (StringUtils.endsWithIgnoreCase(resource, ".groovy")) {
					// When clearly asking for Groovy, that's what they'll get...
					readerClass = GroovyBeanDefinitionReader.class;
				}
				else {
					// Primarily ".xml" files but for any other extension as well
					readerClass = XmlBeanDefinitionReader.class;
				}
			}

			BeanDefinitionReader reader = readerInstanceCache.get(readerClass);
			//如果reader为null，那么新建reader
			if (reader == null) {
				try {
					// Instantiate the specified BeanDefinitionReader
					reader = readerClass.getConstructor(BeanDefinitionRegistry.class).newInstance(this.registry);
					// Delegate the current ResourceLoader to it if possible
					if (reader instanceof AbstractBeanDefinitionReader abdr) {
						abdr.setResourceLoader(this.resourceLoader);
						abdr.setEnvironment(this.environment);
					}
					readerInstanceCache.put(readerClass, reader);
				}
				catch (Throwable ex) {
					throw new IllegalStateException(
							"Could not instantiate BeanDefinitionReader class [" + readerClass.getName() + "]");
				}
			}

			// TODO SPR-6310: qualify relative path locations as done in AbstractContextLoader.modifyLocations
			//最终调用reader的loadBeanDefinitions方法加载resource资源，解析、注册bean定义
			reader.loadBeanDefinitions(resource);
		});
	}

	/**
	 * 注册@Import注解引入的bean定义到注册表中
	 */
	private void loadBeanDefinitionsFromRegistrars(Map<ImportBeanDefinitionRegistrar, AnnotationMetadata> registrars) {
		//循环importBeanDefinitionRegistrars缓存map，回调每一个ImportBeanDefinitionRegistrar对象的三个参数的registerBeanDefinitions方法
		registrars.forEach((registrar, metadata) ->
				registrar.registerBeanDefinitions(metadata, this.registry, this.importBeanNameGenerator));
	}


	/**
	 * {@link RootBeanDefinition} marker subclass used to signify that a bean definition
	 * was created from a configuration class as opposed to any other configuration source.
	 * Used in bean overriding cases where it's necessary to determine whether the bean
	 * definition was created externally.
	 */
	@SuppressWarnings("serial")
	private static class ConfigurationClassBeanDefinition extends RootBeanDefinition implements AnnotatedBeanDefinition {

		private final AnnotationMetadata annotationMetadata;

		private final MethodMetadata factoryMethodMetadata;

		private final String derivedBeanName;

		public ConfigurationClassBeanDefinition(
				ConfigurationClass configClass, MethodMetadata beanMethodMetadata, String derivedBeanName) {

			this.annotationMetadata = configClass.getMetadata();
			this.factoryMethodMetadata = beanMethodMetadata;
			this.derivedBeanName = derivedBeanName;
			setResource(configClass.getResource());
			setLenientConstructorResolution(false);
		}

		public ConfigurationClassBeanDefinition(RootBeanDefinition original,
				ConfigurationClass configClass, MethodMetadata beanMethodMetadata, String derivedBeanName) {
			super(original);
			this.annotationMetadata = configClass.getMetadata();
			this.factoryMethodMetadata = beanMethodMetadata;
			this.derivedBeanName = derivedBeanName;
		}

		private ConfigurationClassBeanDefinition(ConfigurationClassBeanDefinition original) {
			super(original);
			this.annotationMetadata = original.annotationMetadata;
			this.factoryMethodMetadata = original.factoryMethodMetadata;
			this.derivedBeanName = original.derivedBeanName;
		}

		@Override
		public AnnotationMetadata getMetadata() {
			return this.annotationMetadata;
		}

		@Override
		@NonNull
		public MethodMetadata getFactoryMethodMetadata() {
			return this.factoryMethodMetadata;
		}

		@Override
		public boolean isFactoryMethod(Method candidate) {
			return (super.isFactoryMethod(candidate) && BeanAnnotationHelper.isBeanAnnotated(candidate) &&
					BeanAnnotationHelper.determineBeanNameFor(candidate).equals(this.derivedBeanName));
		}

		@Override
		public ConfigurationClassBeanDefinition cloneBeanDefinition() {
			return new ConfigurationClassBeanDefinition(this);
		}
	}


	/**
	 * Evaluate {@code @Conditional} annotations, tracking results and taking into
	 * account 'imported by'.
	 */
	private class TrackedConditionEvaluator {

		private final Map<ConfigurationClass, Boolean> skipped = new HashMap<>();

		/**
		 * 是否应该跳过解析
		 */
		public boolean shouldSkip(ConfigurationClass configClass) {
			//从缓存获取当前configClass的是否应该跳过的结果
			Boolean skip = this.skipped.get(configClass);
			//如果缓存为null，那么解析
			if (skip == null) {
				//如果是被其他类引入的
				if (configClass.isImported()) {
					//所有的引入类被跳过的标记，默认true
					boolean allSkipped = true;
					//获取引入类
					for (ConfigurationClass importedBy : configClass.getImportedBy()) {
						//如果引入类不应该被跳过
						if (!shouldSkip(importedBy)) {
							//那么allSkipped为false，结束循环
							allSkipped = false;
							break;
						}
					}
					//如果所有的引入类被跳过，那么skip设置为true，表示当前被引入的类应该被跳过
					if (allSkipped) {
						// The config classes that imported this one were all skipped, therefore we are skipped...
						skip = true;
					}
				}
				//如果skip还是为null
				if (skip == null) {
					//那么调用conditionEvaluator的shouldSkip方法继续判断，这里的phase生效的阶段参数为REGISTER_BEAN，即注册bean的阶段
					//这个方法我们此前就讲过了
					skip = conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN);
				}
				//存入缓存
				this.skipped.put(configClass, skip);
			}
			//返回skip
			return skip;
		}
	}

}
