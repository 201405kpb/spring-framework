/*
 * Copyright 2002-2023 the original author or authors.
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.context.annotation.DeferredImportSelector.Group;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PropertySourceDescriptor;
import org.springframework.core.io.support.PropertySourceProcessor;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.*;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;

/**
 * Parses a {@link Configuration} class definition, populating a collection of
 * {@link ConfigurationClass} objects (parsing a single Configuration class may result in
 * any number of ConfigurationClass objects because one Configuration class may import
 * another using the {@link Import} annotation).
 *
 * <p>This class helps separate the concern of parsing the structure of a Configuration
 * class from the concern of registering BeanDefinition objects based on the content of
 * that model (with the exception of {@code @ComponentScan} annotations which need to be
 * registered immediately).
 *
 * <p>This ASM-based implementation avoids reflection and eager class loading in order to
 * interoperate effectively with lazy class loading in a Spring ApplicationContext.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @since 3.0
 * @see ConfigurationClassBeanDefinitionReader
 */
class ConfigurationClassParser {

	/**
	 * 如果类名以"java.lang.annotation."或者"org.springframework.stereotype."开头，就返回true，或者返回false
	 */
	private static final Predicate<String> DEFAULT_EXCLUSION_FILTER = className ->
			(className.startsWith("java.lang.annotation.") || className.startsWith("org.springframework.stereotype."));

	private static final Comparator<DeferredImportSelectorHolder> DEFERRED_IMPORT_COMPARATOR =
			(o1, o2) -> AnnotationAwareOrderComparator.INSTANCE.compare(o1.getImportSelector(), o2.getImportSelector());


	private final Log logger = LogFactory.getLog(getClass());

	private final MetadataReaderFactory metadataReaderFactory;

	private final ProblemReporter problemReporter;

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	@Nullable
	private final PropertySourceRegistry propertySourceRegistry;

	private final BeanDefinitionRegistry registry;

	private final ComponentScanAnnotationParser componentScanParser;

	/**
	 * 解析@Conditional条件注解的评估器
	 * Spring 4.0 新增的@Conditional条件注解，可以标注在类或者方法上，在容器启动时用于控制一批或者一个bean实例是否被注入
	 * 通过判断该注解中指定的条件是否满足，如果不满足则不会将对应的bean注入到容器中，如果满足则会将对应的bean进行注入
	 */
	private final ConditionEvaluator conditionEvaluator;

	/**
	 * 以解析的配置类缓存map
	 */
	private final Map<ConfigurationClass, ConfigurationClass> configurationClasses = new LinkedHashMap<>();

	/**
	 * 已知的超类配置类缓存map
	 */
	private final Map<String, ConfigurationClass> knownSuperclasses = new HashMap<>();

	private final ImportStack importStack = new ImportStack();

	private final DeferredImportSelectorHandler deferredImportSelectorHandler = new DeferredImportSelectorHandler();

	private final SourceClass objectSourceClass = new SourceClass(Object.class);


	/**
	 * Create a new {@link ConfigurationClassParser} instance that will be used
	 * to populate the set of configuration classes.
	 */
	public ConfigurationClassParser(MetadataReaderFactory metadataReaderFactory,
			ProblemReporter problemReporter, Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator componentScanBeanNameGenerator, BeanDefinitionRegistry registry) {

		this.metadataReaderFactory = metadataReaderFactory;
		this.problemReporter = problemReporter;
		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.propertySourceRegistry = (this.environment instanceof ConfigurableEnvironment ce ?
				new PropertySourceRegistry(new PropertySourceProcessor(ce, this.resourceLoader)) : null);
		this.registry = registry;
		this.componentScanParser = new ComponentScanAnnotationParser(
				environment, resourceLoader, componentScanBeanNameGenerator, registry);
		this.conditionEvaluator = new ConditionEvaluator(registry, environment, resourceLoader);
	}


	public void parse(Set<BeanDefinitionHolder> configCandidates) {
		// 传入的带解析的配置类是一个集合，但是我们本次debug是springboot第一次启动，此时这个结合只有启动类这一个配置类
		for (BeanDefinitionHolder holder : configCandidates) {
			// 获取 BeanDefinition
			BeanDefinition bd = holder.getBeanDefinition();
			//根据类型调用不同的parse方法，其内部都是调用的processConfigurationClass方法
			try {
				//如果属于AnnotatedBeanDefinition，比如AnnotatedGenericBeanDefinition、ScannedGenericBeanDefinition、ConfigurationClassBeanDefinition
				if (bd instanceof AnnotatedBeanDefinition annotatedBeanDef) {
					//调用另一个parse方法解析，内部调用processConfigurationClass方法
					parse(annotatedBeanDef.getMetadata(), holder.getBeanName());
				}
				//否则，如果是AbstractBeanDefinition类型，比如GenericBeanDefinition，并且已经解析了class
				else if (bd instanceof AbstractBeanDefinition abstractBeanDef && abstractBeanDef.hasBeanClass()) {
					//调用另一个parse方法解析，内部调用processConfigurationClass方法
					parse(abstractBeanDef.getBeanClass(), holder.getBeanName());
				}
				else {
					//调用另一个parse方法解析，内部调用processConfigurationClass方法
					parse(bd.getBeanClassName(), holder.getBeanName());
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to parse configuration class [" + bd.getBeanClassName() + "]", ex);
			}
		}
		// 延迟导入选择器持有者开始执行，该步骤和自动装配逻辑有关，
		// 这里提前说一下，配置类中@import中要导入的类如果实现了DeferredImportSelector接口，就会存在deferredImportSelectorHandler属性
		// 在类解析完成后会调用deferredImportSelectorHandler属性的process方法，开始执行导入
		this.deferredImportSelectorHandler.process();
	}

	protected final void parse(@Nullable String className, String beanName) throws IOException {
		Assert.notNull(className, "No bean class name for configuration class bean definition");
		MetadataReader reader = this.metadataReaderFactory.getMetadataReader(className);
		processConfigurationClass(new ConfigurationClass(reader, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	protected final void parse(Class<?> clazz, String beanName) throws IOException {
		processConfigurationClass(new ConfigurationClass(clazz, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	protected final void parse(AnnotationMetadata metadata, String beanName) throws IOException {
		// 最终都会调用这一个方法 进行解析
		processConfigurationClass(new ConfigurationClass(metadata, beanName), DEFAULT_EXCLUSION_FILTER);
	}

	/**
	 * Validate each {@link ConfigurationClass} object.
	 * @see ConfigurationClass#validate
	 */
	public void validate() {
		for (ConfigurationClass configClass : this.configurationClasses.keySet()) {
			configClass.validate(this.problemReporter);
		}
	}

	public Set<ConfigurationClass> getConfigurationClasses() {
		return this.configurationClasses.keySet();
	}

	List<PropertySourceDescriptor> getPropertySourceDescriptors() {
		return (this.propertySourceRegistry != null ? this.propertySourceRegistry.getDescriptors()
				: Collections.emptyList());
	}

	protected void processConfigurationClass(ConfigurationClass configClass, Predicate<String> filter) throws IOException {
		// 判断这个配置类上面有没有 @Condition 注解，如果有判断是否成立，不成立不需要解析
		if (this.conditionEvaluator.shouldSkip(configClass.getMetadata(), ConfigurationPhase.PARSE_CONFIGURATION)) {
			return;
		}
		// 从已经解析过的类集合中获取这个类
		ConfigurationClass existingClass = this.configurationClasses.get(configClass);
		// 如果已经存在了
		if (existingClass != null) {
			// 则判断这个类是不是从其他类里面导入的
			if (configClass.isImported()) {
				// 判断已经存在的类是不是从其他类里导入的
				if (existingClass.isImported()) {
					// 如果成立则将导入类合并
					existingClass.mergeImportedBy(configClass);
				}
				// Otherwise ignore new imported config class; existing non-imported class overrides it.
				return;
			}
			else {
				// Explicit bean definition found, probably replacing an import.
				// Let's remove the old one and go with the new one.
				// 如果这个类不是从其他类导入，则从配置类中删除
				this.configurationClasses.remove(configClass);
				this.knownSuperclasses.values().removeIf(configClass::equals);
			}
		}

		// Recursively process the configuration class and its superclass hierarchy.
		// 处理配置类，由于配置类可能存在父类（若父类的全类名是以java开头的，则除外），所有需要将configClass变成sourceClass 去解析，然后则返回 sourceClass 的父类
		//如果父类为空，则不会进行while循环去解析，如果父类不为空则会循环去解析父类
		// sourceClass 是意义；简单的包装类，目的是为了以统一的方式去处理带注解的类，不管这些类是如何加载的
		SourceClass sourceClass = asSourceClass(configClass, filter);
		do {
			// 开始执行真正的解析各种注解类
			sourceClass = doProcessConfigurationClass(configClass, sourceClass, filter);
		}
		while (sourceClass != null);
		// 将解析过的配置类加入配置类集合，后面转换为BeanDefinition
		this.configurationClasses.put(configClass, configClass);
	}

	/**
	 * Apply processing and build a complete {@link ConfigurationClass} by reading the
	 * annotations, members and methods from the source class. This method can be called
	 * multiple times as relevant sources are discovered.
	 * 通过从sourceClass读取注解、成员和方法，应用处理并构建完整的ConfigurationClass。当发现多个相关sourceClass时，可以多次调用此方法。
	 * @param configClass the configuration class being build 表示当前配置类的对象
	 * @param sourceClass a source class 源类
	 * @return the superclass, or {@code null} if none found or previously processed 超类的sourceClass，如果未找到或以前处理过就返回null
	 */
	@Nullable
	protected final SourceClass doProcessConfigurationClass(
			ConfigurationClass configClass, SourceClass sourceClass, Predicate<String> filter)
			throws IOException {
		// 第一步，首先判断传入的配置类有没有@Component注解，如果有，则需要检查配置类中有没有内部类，并且这个内部类是不是配置类
		if (configClass.getMetadata().isAnnotated(Component.class.getName())) {
			// Recursively process any member (nested) classes first
			//递归处理任何成员（嵌套）类，也就是内部类。内部配置类最终还是会调用processConfigurationClass方法
			processMemberClasses(configClass, sourceClass, filter);
		}

		// Process any @PropertySource annotations
		// 第二步，获取配置类上面的@PropertySource注解和@PropertySources注解，进行解析
		for (AnnotationAttributes propertySource : AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), PropertySources.class,
				org.springframework.context.annotation.PropertySource.class)) {
			if (this.propertySourceRegistry != null) {
				//调用processPropertySource处理通过@PropertySource注解引入的属性源，会将引入的属性源加入到environment环境变量中
				this.propertySourceRegistry.processPropertySource(propertySource);
			}
			else {
				logger.info("Ignoring @PropertySource annotation on [" + sourceClass.getMetadata().getClassName() +
						"]. Reason: Environment must implement ConfigurableEnvironment");
			}
		}

		// Process any @ComponentScan annotations
		// 第三步，获取配置类上面的@ComponentScan注解进行解析
		// 首先获取类上的@ ComponentScans和@ComponentScan注解的所有属性
		Set<AnnotationAttributes> componentScans = AnnotationConfigUtils.attributesForRepeatable(
				sourceClass.getMetadata(), ComponentScans.class, ComponentScan.class);
		//如果存在@ComponentScan注解，并且shouldSkip方法返回false，即不应该跳过，这里的phase生效的阶段参数为REGISTER_BEAN，即注册bean的阶段
		//这两个条件都满足，那么可以解析继续@ComponentScan注解，进而继续扫描包，注册bean定义
		if (!componentScans.isEmpty() &&
				!this.conditionEvaluator.shouldSkip(sourceClass.getMetadata(), ConfigurationPhase.REGISTER_BEAN)) {
			//遍历componentScans集合
			for (AnnotationAttributes componentScan : componentScans) {
				// The config class is annotated with @ComponentScan -> perform the scan immediately
				// 解析@ ComponentScans和@ComponentScan注解 配置的扫描的包所包含的类
				Set<BeanDefinitionHolder> scannedBeanDefinitions =
						this.componentScanParser.parse(componentScan, sourceClass.getMetadata().getClassName());
				// Check the set of scanned definitions for any further config classes and parse recursively if needed
				// 检查扫描到的bean定义集合，以查找任何的配置类，并根据需要递归的解析
				for (BeanDefinitionHolder holder : scannedBeanDefinitions) {
					BeanDefinition bdCand = holder.getBeanDefinition().getOriginatingBeanDefinition();
					if (bdCand == null) {
						bdCand = holder.getBeanDefinition();
					}
					//调用checkConfigurationClassCandidate判断是否是配置类并设置属性
					if (ConfigurationClassUtils.checkConfigurationClassCandidate(bdCand, this.metadataReaderFactory)) {
						// 如果扫描到的bean定义是配置类，那么调用parse方法解析配置类，内部还是递归调用的processConfigurationClass方法
						parse(bdCand.getBeanClassName(), holder.getBeanName());
					}
				}
			}
		}

		// Process any @Import annotations
		// 第四步，解析配置类上面的@Import注解，自动装配逻辑有关，也是我们后面重点关注的
		// 关于getImports方法，一个注解可能是被多个注解标注，就像我们这次要看的@SpringBootApplication，它里面有 @SpringBootConfiguration 和 @EnableAutoConfiguration 两个注解
		// 而这两个注解里面又都被 @Import注解标注，所以在解析@Import注解前，要先找到所有的@Import注解，这里使用的是递归的方式，而且每个@Import注解可以导入多个类，我们就不看这个方法了，只需要知道我们再debug时，
		// 第一次解析的配置类就是 springboot 的启动类，而启动类上的@SpringBootApplication最终会解析出来两个 @Import 导入的类
		processImports(configClass, sourceClass, getImports(sourceClass), filter, true);

		// Process any @ImportResource annotations
		// 第五步，获取配置类上面的@ImportResource注解进行解析
		AnnotationAttributes importResource =
				AnnotationConfigUtils.attributesFor(sourceClass.getMetadata(), ImportResource.class);
		//如果不为null，说明存在@ImportResource注解
		if (importResource != null) {
			//获取locations属性数组，就是XML配置文件的路径字符串
			String[] resources = importResource.getStringArray("locations");
			//获取reader属性，用来读取配置文件中的bean定义
			Class<? extends BeanDefinitionReader> readerClass = importResource.getClass("reader");
			//遍历配置文件路径
			for (String resource : resources) {
				//使用environment环境变量解析路径字符串的占位符
				String resolvedResource = this.environment.resolveRequiredPlaceholders(resource);
				//仅仅是存入configClass的importedResources缓存中，后续再处理
				configClass.addImportedResource(resolvedResource, readerClass);
			}
		}

		// Process individual @Bean methods
		// 第六步，获取配置类里面标志@Bean注解的方法进行解析
		Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(sourceClass);
		for (MethodMetadata methodMetadata : beanMethods) {
			// 仅仅是存入configClass的beanMethods缓存中，后续再处理
			configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
		}

		// Process default methods on interfaces
		// 第七步，判断配置类是否有实现接口，如果有检查接口中的方法是否有@bean注解，如果有进行解析
		processInterfaces(configClass, sourceClass);

		// Process superclass, if any
		// 第八步，判断配置类是否有父类，如果有，获取父类返回，在进行一次解析
		if (sourceClass.getMetadata().hasSuperClass()) {
			String superclass = sourceClass.getMetadata().getSuperClassName();
			if (superclass != null && !superclass.startsWith("java") &&
					!this.knownSuperclasses.containsKey(superclass)) {
				this.knownSuperclasses.put(superclass, configClass);
				// Superclass found, return its annotation metadata and recurse
				// 返回父类的sourceClass，将会在外层的processConfigurationClass中进行下一次循环解析父类。
				return sourceClass.getSuperClass();
			}
		}

		// No superclass -> processing is complete
		// 如果没有符合规则的父类或者都解析完毕，那么返回null，将会在外层的processConfigurationClass中跳出循环，进行后续步骤
		return null;
	}

	/**
	 * Register member (nested) classes that happen to be configuration classes themselves.
	 * 解析、注册 配置类内部的成员（嵌套）类，也就是内部类。最终还是会调用外部的doProcessConfigurationClass方法
	 */
	private void processMemberClasses(ConfigurationClass configClass, SourceClass sourceClass,
									  Predicate<String> filter) throws IOException {
		/*
		 * 获取全部内部类的SourceClass集合memberClasses
		 *
		 * 还记得我们之前在IoC容器初始化的时候，讲的扩展标签解析的doScan方法内的第二个isCandidateComponent方法吗
		 * 该方法将非静态内部类都排除了，因此，非静态内部类的bean定义还没有注册到容器中。
		 *
		 * 而这里的getMemberClasses将获取所有的内部类，包括静态的和非静态的，其中，非静态内部类的bean定义就是在这里注册的
		 */
		Collection<SourceClass> memberClasses = sourceClass.getMemberClasses();
		//如果memberClasses不为空
		if (!memberClasses.isEmpty()) {
			//需要处理的配置类集合
			List<SourceClass> candidates = new ArrayList<>(memberClasses.size());
			for (SourceClass memberClass : memberClasses) {
				//如果内部类也是配置类
				if (ConfigurationClassUtils.isConfigurationCandidate(memberClass.getMetadata()) &&
						!memberClass.getMetadata().getClassName().equals(configClass.getMetadata().getClassName())) {
					//加入到candidates
					candidates.add(memberClass);
				}
			}
			//同样需要排序，这里的OrderComparator就不支持注解了
			OrderComparator.sort(candidates);
			/*
			 * 遍历candidates，按照排序顺序依次处理
			 */
			for (SourceClass candidate : candidates) {
				//如果importStack包含此外部配置类，那么说明import循环依赖，直接抛出异常: "A circular @Import has been detected……"
				//importStack属性用于判断import重复依赖
				if (this.importStack.contains(configClass)) {
					this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
				} else {
					//当前外部配置类configClass入栈
					this.importStack.push(configClass);
					try {
						/*
						 * 调用processConfigurationClass方法，处理当前内部配置类
						 * 这里的asConfigClass方法将当前外部配置类的设置到内部配置类的importedBy集合中，表示算作被外部类import引入进来的
						 */
						processConfigurationClass(candidate.asConfigClass(configClass), filter);
					} finally {
						//当前外部配置类configClass出栈
						this.importStack.pop();
					}
				}
			}
		}
	}

	/**
	 * Register default methods on interfaces implemented by the configuration class.
	 */
	private void processInterfaces(ConfigurationClass configClass, SourceClass sourceClass) throws IOException {
		for (SourceClass ifc : sourceClass.getInterfaces()) {
			Set<MethodMetadata> beanMethods = retrieveBeanMethodMetadata(ifc);
			for (MethodMetadata methodMetadata : beanMethods) {
				if (!methodMetadata.isAbstract()) {
					// A default method or other concrete method on a Java 8+ interface...
					configClass.addBeanMethod(new BeanMethod(methodMetadata, configClass));
				}
			}
			processInterfaces(configClass, ifc);
		}
	}

	/**
	 * Retrieve the metadata for all <code>@Bean</code> methods.
	 */
	private Set<MethodMetadata> retrieveBeanMethodMetadata(SourceClass sourceClass) {
		AnnotationMetadata original = sourceClass.getMetadata();
		// 通过 @Bean 获取到所有的 MethodMetadata , 这里的 Set 实际上是 LinkedHashSet
		Set<MethodMetadata> beanMethods = original.getAnnotatedMethods(Bean.class.getName());
		if (beanMethods.size() > 1 && original instanceof StandardAnnotationMetadata) {
			// Try reading the class file via ASM for deterministic declaration order...
			// Unfortunately, the JVM's standard reflection returns methods in arbitrary
			// order, even between different runs of the same application on the same JVM.
			//尝试通过ASM读取类文件以确定声明顺序…不幸的是，JVM的标准反射以任意顺序返回方法，
			// 即使在同一JVM上同一应用程序的不同运行之间也是如此
			try {
				AnnotationMetadata asm =
						this.metadataReaderFactory.getMetadataReader(original.getClassName()).getAnnotationMetadata();
				Set<MethodMetadata> asmMethods = asm.getAnnotatedMethods(Bean.class.getName());
				if (asmMethods.size() >= beanMethods.size()) {
					Set<MethodMetadata> selectedMethods = new LinkedHashSet<>(asmMethods.size());
					for (MethodMetadata asmMethod : asmMethods) {
						for (MethodMetadata beanMethod : beanMethods) {
							if (beanMethod.getMethodName().equals(asmMethod.getMethodName())) {
								selectedMethods.add(beanMethod);
								break;
							}
						}
					}
					if (selectedMethods.size() == beanMethods.size()) {
						// All reflection-detected methods found in ASM method set -> proceed
						//在ASM方法集中找到的所有反射检测方法
						beanMethods = selectedMethods;
					}
				}
			}
			catch (IOException ex) {
				logger.debug("Failed to read class file via ASM for determining @Bean method order", ex);
				// No worries, let's continue with the reflection metadata we started with...
			}
		}
		return beanMethods;
	}


	/**
	 * Returns {@code @Import} class, considering all meta-annotations.
	 */
	private Set<SourceClass> getImports(SourceClass sourceClass) throws IOException {
		Set<SourceClass> imports = new LinkedHashSet<>();
		Set<SourceClass> visited = new LinkedHashSet<>();
		collectImports(sourceClass, imports, visited);
		return imports;
	}

	/**
	 * Recursively collect all declared {@code @Import} values. Unlike most
	 * meta-annotations it is valid to have several {@code @Import}s declared with
	 * different values; the usual process of returning values from the first
	 * meta-annotation on a class is not sufficient.
	 * <p>For example, it is common for a {@code @Configuration} class to declare direct
	 * {@code @Import}s in addition to meta-imports originating from an {@code @Enable}
	 * annotation.
	 * @param sourceClass the class to search
	 * @param imports the imports collected so far
	 * @param visited used to track visited classes to prevent infinite recursion
	 * @throws IOException if there is any problem reading metadata from the named class
	 */
	private void collectImports(SourceClass sourceClass, Set<SourceClass> imports, Set<SourceClass> visited)
			throws IOException {

		if (visited.add(sourceClass)) {
			for (SourceClass annotation : sourceClass.getAnnotations()) {
				String annName = annotation.getMetadata().getClassName();
				if (!annName.equals(Import.class.getName())) {
					collectImports(annotation, imports, visited);
				}
			}
			imports.addAll(sourceClass.getAnnotationAttributes(Import.class.getName(), "value"));
		}
	}

	private void processImports(ConfigurationClass configClass, SourceClass currentSourceClass,
			Collection<SourceClass> importCandidates, Predicate<String> exclusionFilter,
			boolean checkForCircularImports) {
		// 判断是否有要导入的类
		if (importCandidates.isEmpty()) {
			return;
		}
		// checkForCircularImports传入就是true,isChainedImportOnStack方法执行结果也是true
		if (checkForCircularImports && isChainedImportOnStack(configClass)) {
			this.problemReporter.error(new CircularImportProblem(configClass, this.importStack));
		}
		else {
			this.importStack.push(configClass);
			try {
				// 遍历要导入的类
				for (SourceClass candidate : importCandidates) {
					// 如果这个类实现了ImportSelector这个接口
					if (candidate.isAssignable(ImportSelector.class)) {
						// Candidate class is an ImportSelector -> delegate to it to determine imports
						Class<?> candidateClass = candidate.loadClass();
						// 将这个类实例化
						ImportSelector selector = ParserStrategyUtils.instantiateClass(candidateClass, ImportSelector.class,
								this.environment, this.resourceLoader, this.registry);
						Predicate<String> selectorFilter = selector.getExclusionFilter();
						if (selectorFilter != null) {
							exclusionFilter = exclusionFilter.or(selectorFilter);
						}
						// 判断这个类是否实现了DeferredImportSelector接口，如果实现了就加入deferredImportSelectorHandler处理器，这一步和自动装配有关，所以整个流程中只有这一步是我们关注的重点
						// 我们所熟知的@SpringBootApplication 中就有一个@AutoConfigurationPackage注解，
						// 而整个@AutoConfigurationPackage注解中就有 @Import注解，他导入的类AutoConfigurationImportSelector，就实现了这个接口
						if (selector instanceof DeferredImportSelector deferredImportSelectorHandler) {
							this.deferredImportSelectorHandler.handle(configClass, deferredImportSelectorHandler);
						}
						else {
							// 否则就再次进行一次要导入类的解析
							String[] importClassNames = selector.selectImports(currentSourceClass.getMetadata());
							Collection<SourceClass> importSourceClasses = asSourceClasses(importClassNames, exclusionFilter);
							processImports(configClass, currentSourceClass, importSourceClasses, exclusionFilter, false);
						}
					}
					// 如果这个类实现了ImportBeanDefinitionRegistrar这个接口，就将这个类实例化，然后存入当前正在解析的配置类
					else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) {
						// Candidate class is an ImportBeanDefinitionRegistrar ->
						// delegate to it to register additional bean definitions
						Class<?> candidateClass = candidate.loadClass();
						ImportBeanDefinitionRegistrar registrar =
								ParserStrategyUtils.instantiateClass(candidateClass, ImportBeanDefinitionRegistrar.class,
										this.environment, this.resourceLoader, this.registry);
						configClass.addImportBeanDefinitionRegistrar(registrar, currentSourceClass.getMetadata());
					}
					else {
						// Candidate class not an ImportSelector or ImportBeanDefinitionRegistrar ->
						// process it as an @Configuration class
						// 如果这个要导入的类上面两个接口都没实现，则就对这个类进行解析，可能这个类是一个配置类
						this.importStack.registerImport(
								currentSourceClass.getMetadata(), candidate.getMetadata().getClassName());
						processConfigurationClass(candidate.asConfigClass(configClass), exclusionFilter);
					}
				}
			}
			catch (BeanDefinitionStoreException ex) {
				throw ex;
			}
			catch (Throwable ex) {
				throw new BeanDefinitionStoreException(
						"Failed to process import candidates for configuration class [" +
						configClass.getMetadata().getClassName() + "]: " + ex.getMessage(), ex);
			}
			finally {
				this.importStack.pop();
			}
		}
	}

	private boolean isChainedImportOnStack(ConfigurationClass configClass) {
		if (this.importStack.contains(configClass)) {
			String configClassName = configClass.getMetadata().getClassName();
			AnnotationMetadata importingClass = this.importStack.getImportingClassFor(configClassName);
			while (importingClass != null) {
				if (configClassName.equals(importingClass.getClassName())) {
					return true;
				}
				importingClass = this.importStack.getImportingClassFor(importingClass.getClassName());
			}
		}
		return false;
	}

	ImportRegistry getImportRegistry() {
		return this.importStack;
	}


	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link ConfigurationClass}.
	 */
	private SourceClass asSourceClass(ConfigurationClass configurationClass, Predicate<String> filter) throws IOException {
		AnnotationMetadata metadata = configurationClass.getMetadata();
		if (metadata instanceof StandardAnnotationMetadata standardAnnotationMetadata) {
			return asSourceClass(standardAnnotationMetadata.getIntrospectedClass(), filter);
		}
		return asSourceClass(metadata.getClassName(), filter);
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a {@link Class}.
	 */
	SourceClass asSourceClass(@Nullable Class<?> classType, Predicate<String> filter) throws IOException {
		if (classType == null || filter.test(classType.getName())) {
			return this.objectSourceClass;
		}
		try {
			// Sanity test that we can reflectively read annotations,
			// including Class attributes; if not -> fall back to ASM
			for (Annotation ann : classType.getDeclaredAnnotations()) {
				AnnotationUtils.validateAnnotation(ann);
			}
			return new SourceClass(classType);
		}
		catch (Throwable ex) {
			// Enforce ASM via class name resolution
			return asSourceClass(classType.getName(), filter);
		}
	}

	/**
	 * Factory method to obtain a {@link SourceClass} collection from class names.
	 */
	private Collection<SourceClass> asSourceClasses(String[] classNames, Predicate<String> filter) throws IOException {
		List<SourceClass> annotatedClasses = new ArrayList<>(classNames.length);
		for (String className : classNames) {
			annotatedClasses.add(asSourceClass(className, filter));
		}
		return annotatedClasses;
	}

	/**
	 * Factory method to obtain a {@link SourceClass} from a class name.
	 * 获取符合filter条件的类名的SourceClass
	 */
	SourceClass asSourceClass(@Nullable String className, Predicate<String> filter) throws IOException {
		//如果符合过滤条件，那么返回objectSourceClass常量，后续将不会注入该类型的bean实例
		//注意这里还是返回了一个objectSourceClass，而不是null，后面会处理
		if (className == null || filter.test(className)) {
			return this.objectSourceClass;
		}
		//如果class名以java开头，这表示位于rt.jar核心包中的核心类
		if (className.startsWith("java")) {
			// Never use ASM for core java types
			try {
				//直接传递生成的class对象
				return new SourceClass(ClassUtils.forName(className, this.resourceLoader.getClassLoader()));
			} catch (ClassNotFoundException ex) {
				throw new IOException("Failed to load class [" + className + "]", ex);
			}
		}
		//根据className，返回SourceClass
		return new SourceClass(this.metadataReaderFactory.getMetadataReader(className));
	}


	@SuppressWarnings("serial")
	private static class ImportStack extends ArrayDeque<ConfigurationClass> implements ImportRegistry {

		private final MultiValueMap<String, AnnotationMetadata> imports = new LinkedMultiValueMap<>();

		public void registerImport(AnnotationMetadata importingClass, String importedClass) {
			this.imports.add(importedClass, importingClass);
		}

		@Override
		@Nullable
		public AnnotationMetadata getImportingClassFor(String importedClass) {
			return CollectionUtils.lastElement(this.imports.get(importedClass));
		}

		@Override
		public void removeImportingClass(String importingClass) {
			for (List<AnnotationMetadata> list : this.imports.values()) {
				for (Iterator<AnnotationMetadata> iterator = list.iterator(); iterator.hasNext();) {
					if (iterator.next().getClassName().equals(importingClass)) {
						iterator.remove();
						break;
					}
				}
			}
		}

		/**
		 * Given a stack containing (in order)
		 * <ul>
		 * <li>com.acme.Foo</li>
		 * <li>com.acme.Bar</li>
		 * <li>com.acme.Baz</li>
		 * </ul>
		 * return "[Foo->Bar->Baz]".
		 */
		@Override
		public String toString() {
			StringJoiner joiner = new StringJoiner("->", "[", "]");
			for (ConfigurationClass configurationClass : this) {
				joiner.add(configurationClass.getSimpleName());
			}
			return joiner.toString();
		}
	}


	private class DeferredImportSelectorHandler {

		@Nullable
		private List<DeferredImportSelectorHolder> deferredImportSelectors = new ArrayList<>();

		/**
		 * Handle the specified {@link DeferredImportSelector}. If deferred import
		 * selectors are being collected, this registers this instance to the list. If
		 * they are being processed, the {@link DeferredImportSelector} is also processed
		 * immediately according to its {@link DeferredImportSelector.Group}.
		 * @param configClass the source configuration class
		 * @param importSelector the selector to handle
		 */
		public void handle(ConfigurationClass configClass, DeferredImportSelector importSelector) {
			// 将要导入的类，包装成 DeferredImportSelectorHolder类型，然后将它加入到deferredImportSelectors集合中，在上面我们就发现，配置类
			DeferredImportSelectorHolder holder = new DeferredImportSelectorHolder(configClass, importSelector);
			// deferredImportSelectors 这里是true，deferredImportSelectorHandler在创建的时候就会初始化deferredImportSelectors属性，
			// deferredImportSelectors是一个集合，里面存储DeferredImportSelectorHolder类型
			if (this.deferredImportSelectors == null) {
				DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
				handler.register(holder);
				handler.processGroupImports();
			}
			else {
				this.deferredImportSelectors.add(holder);
			}
		}

		public void process() {
			// 获取之前配置类解析过程中所有@Import注解导入的实现DeferredImportSelector接口的类的持有者类，注意：是类的持有者类
			List<DeferredImportSelectorHolder> deferredImports = this.deferredImportSelectors;
			this.deferredImportSelectors = null;
			try {
				if (deferredImports != null) {
					// 创建一个DeferredImportSelectorGroupingHandler对象
					DeferredImportSelectorGroupingHandler handler = new DeferredImportSelectorGroupingHandler();
					// 将deferredImports集合里面的要导入的类排序
					deferredImports.sort(DEFERRED_IMPORT_COMPARATOR);
					// 将要导入的类遍历添加到DeferredImportSelectorGroupingHandler对象中，见标题1.2.1
					deferredImports.forEach(handler::register);
					// DeferredImportSelectorGroupingHandler 开始执行导入，见表格1.2.2
					handler.processGroupImports();
				}
			}
			finally {
				// 在导入类解析完成后，将deferredImportSelectors重置，清空
				this.deferredImportSelectors = new ArrayList<>();
			}
		}
	}


	private class DeferredImportSelectorGroupingHandler {

		private final Map<Object, DeferredImportSelectorGrouping> groupings = new LinkedHashMap<>();

		private final Map<AnnotationMetadata, ConfigurationClass> configurationClasses = new HashMap<>();

		public void register(DeferredImportSelectorHolder deferredImport) {
			// 获取DeferredImportSelectorHolder中的AutoConfigurationImportSelector的AutoConfigurationGroup
			Class<? extends Group> group = deferredImport.getImportSelector().getImportGroup();
			// 然后将AutoConfigurationGroup包装成DeferredImportSelectorGrouping
			// 再以DeferredImportSelectorHolder或者AutoConfigurationGroup为key,以DeferredImportSelectorGrouping为值存入groupings集合，返回的就是DeferredImportSelectorGrouping
			DeferredImportSelectorGrouping grouping = this.groupings.computeIfAbsent(
					(group != null ? group : deferredImport),
					key -> new DeferredImportSelectorGrouping(createGroup(group)));
			// 在把DeferredImportSelectorHolder加入DeferredImportSelectorGrouping
			grouping.add(deferredImport);
			this.configurationClasses.put(deferredImport.getConfigurationClass().getMetadata(),
					deferredImport.getConfigurationClass());
		}

		public void processGroupImports() {
			// 获取到之前所有的grouping，遍历执行
			for (DeferredImportSelectorGrouping grouping : this.groupings.values()) {
				Predicate<String> exclusionFilter = grouping.getCandidateFilter();
				// 把解析出来的配置类全部存入ConfigurationClassParser类中的configurationClasses集合中
				grouping.getImports().forEach(entry -> {
					ConfigurationClass configurationClass = this.configurationClasses.get(entry.getMetadata());
					try {
						processImports(configurationClass, asSourceClass(configurationClass, exclusionFilter),
								Collections.singleton(asSourceClass(entry.getImportClassName(), exclusionFilter)),
								exclusionFilter, false);
					}
					catch (BeanDefinitionStoreException ex) {
						throw ex;
					}
					catch (Throwable ex) {
						throw new BeanDefinitionStoreException(
								"Failed to process import candidates for configuration class [" +
										configurationClass.getMetadata().getClassName() + "]", ex);
					}
				});
			}
		}

		private Group createGroup(@Nullable Class<? extends Group> type) {
			Class<? extends Group> effectiveType = (type != null ? type : DefaultDeferredImportSelectorGroup.class);
			return ParserStrategyUtils.instantiateClass(effectiveType, Group.class,
					ConfigurationClassParser.this.environment,
					ConfigurationClassParser.this.resourceLoader,
					ConfigurationClassParser.this.registry);
		}
	}


	private static class DeferredImportSelectorHolder {

		private final ConfigurationClass configurationClass;

		private final DeferredImportSelector importSelector;

		public DeferredImportSelectorHolder(ConfigurationClass configClass, DeferredImportSelector selector) {
			this.configurationClass = configClass;
			this.importSelector = selector;
		}

		public ConfigurationClass getConfigurationClass() {
			return this.configurationClass;
		}

		public DeferredImportSelector getImportSelector() {
			return this.importSelector;
		}
	}


	private static class DeferredImportSelectorGrouping {

		private final DeferredImportSelector.Group group;

		private final List<DeferredImportSelectorHolder> deferredImports = new ArrayList<>();

		DeferredImportSelectorGrouping(Group group) {
			this.group = group;
		}

		public void add(DeferredImportSelectorHolder deferredImport) {
			this.deferredImports.add(deferredImport);
		}

		/**
		 * Return the imports defined by the group.
		 * @return each import with its associated configuration class
		 */
		public Iterable<Group.Entry> getImports() {
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				// 执行，获取到所有配置到spring.factory的配置类
				this.group.process(deferredImport.getConfigurationClass().getMetadata(),
						deferredImport.getImportSelector());
			}
			// 将上面获取到的所有配置类进行配置类解析为Entry
			return this.group.selectImports();
		}

		public Predicate<String> getCandidateFilter() {
			Predicate<String> mergedFilter = DEFAULT_EXCLUSION_FILTER;
			for (DeferredImportSelectorHolder deferredImport : this.deferredImports) {
				Predicate<String> selectorFilter = deferredImport.getImportSelector().getExclusionFilter();
				if (selectorFilter != null) {
					mergedFilter = mergedFilter.or(selectorFilter);
				}
			}
			return mergedFilter;
		}
	}


	private static class DefaultDeferredImportSelectorGroup implements Group {

		private final List<Entry> imports = new ArrayList<>();

		@Override
		public void process(AnnotationMetadata metadata, DeferredImportSelector selector) {
			for (String importClassName : selector.selectImports(metadata)) {
				this.imports.add(new Entry(metadata, importClassName));
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			return this.imports;
		}
	}


	/**
	 * Simple wrapper that allows annotated source classes to be dealt with
	 * in a uniform manner, regardless of how they are loaded.
	 */
	private class SourceClass implements Ordered {

		private final Object source;  // Class or MetadataReader

		private final AnnotationMetadata metadata;

		public SourceClass(Object source) {
			this.source = source;
			if (source instanceof Class<?> sourceClass) {
				this.metadata = AnnotationMetadata.introspect(sourceClass);
			}
			else {
				this.metadata = ((MetadataReader) source).getAnnotationMetadata();
			}
		}

		public final AnnotationMetadata getMetadata() {
			return this.metadata;
		}

		@Override
		public int getOrder() {
			Integer order = ConfigurationClassUtils.getOrder(this.metadata);
			return (order != null ? order : Ordered.LOWEST_PRECEDENCE);
		}

		public Class<?> loadClass() throws ClassNotFoundException {
			if (this.source instanceof Class<?> sourceClass) {
				return sourceClass;
			}
			String className = ((MetadataReader) this.source).getClassMetadata().getClassName();
			return ClassUtils.forName(className, resourceLoader.getClassLoader());
		}

		public boolean isAssignable(Class<?> clazz) throws IOException {
			if (this.source instanceof Class<?> sourceClass) {
				return clazz.isAssignableFrom(sourceClass);
			}
			return new AssignableTypeFilter(clazz).match((MetadataReader) this.source, metadataReaderFactory);
		}

		public ConfigurationClass asConfigClass(ConfigurationClass importedBy) {
			if (this.source instanceof Class<?> sourceClass) {
				return new ConfigurationClass(sourceClass, importedBy);
			}
			return new ConfigurationClass((MetadataReader) this.source, importedBy);
		}

		public Collection<SourceClass> getMemberClasses() throws IOException {
			Object sourceToProcess = this.source;
			if (sourceToProcess instanceof Class<?> sourceClass) {
				try {
					Class<?>[] declaredClasses = sourceClass.getDeclaredClasses();
					List<SourceClass> members = new ArrayList<>(declaredClasses.length);
					for (Class<?> declaredClass : declaredClasses) {
						members.add(asSourceClass(declaredClass, DEFAULT_EXCLUSION_FILTER));
					}
					return members;
				}
				catch (NoClassDefFoundError err) {
					// getDeclaredClasses() failed because of non-resolvable dependencies
					// -> fall back to ASM below
					sourceToProcess = metadataReaderFactory.getMetadataReader(sourceClass.getName());
				}
			}

			// ASM-based resolution - safe for non-resolvable classes as well
			MetadataReader sourceReader = (MetadataReader) sourceToProcess;
			String[] memberClassNames = sourceReader.getClassMetadata().getMemberClassNames();
			List<SourceClass> members = new ArrayList<>(memberClassNames.length);
			for (String memberClassName : memberClassNames) {
				try {
					members.add(asSourceClass(memberClassName, DEFAULT_EXCLUSION_FILTER));
				}
				catch (IOException ex) {
					// Let's skip it if it's not resolvable - we're just looking for candidates
					if (logger.isDebugEnabled()) {
						logger.debug("Failed to resolve member class [" + memberClassName +
								"] - not considering it as a configuration class candidate");
					}
				}
			}
			return members;
		}

		public SourceClass getSuperClass() throws IOException {
			if (this.source instanceof Class<?> sourceClass) {
				return asSourceClass(sourceClass.getSuperclass(), DEFAULT_EXCLUSION_FILTER);
			}
			return asSourceClass(
					((MetadataReader) this.source).getClassMetadata().getSuperClassName(), DEFAULT_EXCLUSION_FILTER);
		}

		public Set<SourceClass> getInterfaces() throws IOException {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class<?> sourceClass) {
				for (Class<?> ifcClass : sourceClass.getInterfaces()) {
					result.add(asSourceClass(ifcClass, DEFAULT_EXCLUSION_FILTER));
				}
			}
			else {
				for (String className : this.metadata.getInterfaceNames()) {
					result.add(asSourceClass(className, DEFAULT_EXCLUSION_FILTER));
				}
			}
			return result;
		}

		public Set<SourceClass> getAnnotations() {
			Set<SourceClass> result = new LinkedHashSet<>();
			if (this.source instanceof Class<?> sourceClass) {
				for (Annotation ann : sourceClass.getDeclaredAnnotations()) {
					Class<?> annType = ann.annotationType();
					if (!annType.getName().startsWith("java")) {
						try {
							result.add(asSourceClass(annType, DEFAULT_EXCLUSION_FILTER));
						}
						catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			else {
				for (String className : this.metadata.getAnnotationTypes()) {
					if (!className.startsWith("java")) {
						try {
							result.add(getRelated(className));
						}
						catch (Throwable ex) {
							// An annotation not present on the classpath is being ignored
							// by the JVM's class loading -> ignore here as well.
						}
					}
				}
			}
			return result;
		}

		public Collection<SourceClass> getAnnotationAttributes(String annType, String attribute) throws IOException {
			Map<String, Object> annotationAttributes = this.metadata.getAnnotationAttributes(annType, true);
			if (annotationAttributes == null || !annotationAttributes.containsKey(attribute)) {
				return Collections.emptySet();
			}
			String[] classNames = (String[]) annotationAttributes.get(attribute);
			Set<SourceClass> result = new LinkedHashSet<>();
			for (String className : classNames) {
				result.add(getRelated(className));
			}
			return result;
		}

		private SourceClass getRelated(String className) throws IOException {
			if (this.source instanceof Class<?> sourceClass) {
				try {
					Class<?> clazz = ClassUtils.forName(className, sourceClass.getClassLoader());
					return asSourceClass(clazz, DEFAULT_EXCLUSION_FILTER);
				}
				catch (ClassNotFoundException ex) {
					// Ignore -> fall back to ASM next, except for core java types.
					if (className.startsWith("java")) {
						throw new IOException("Failed to load class [" + className + "]", ex);
					}
					return new SourceClass(metadataReaderFactory.getMetadataReader(className));
				}
			}
			return asSourceClass(className, DEFAULT_EXCLUSION_FILTER);
		}

		@Override
		public boolean equals(@Nullable Object obj) {
			return (this == obj || (obj instanceof SourceClass that &&
					this.metadata.getClassName().equals(that.metadata.getClassName())));
		}

		@Override
		public int hashCode() {
			return this.metadata.getClassName().hashCode();
		}

		@Override
		public String toString() {
			return this.metadata.getClassName();
		}
	}


	/**
	 * {@link Problem} registered upon detection of a circular {@link Import}.
	 */
	private static class CircularImportProblem extends Problem {

		public CircularImportProblem(ConfigurationClass attemptedImport, Deque<ConfigurationClass> importStack) {
			super(String.format("A circular @Import has been detected: " +
					"Illegal attempt by @Configuration class '%s' to import class '%s' as '%s' is " +
					"already present in the current import stack %s", importStack.element().getSimpleName(),
					attemptedImport.getSimpleName(), attemptedImport.getSimpleName(), importStack),
					new Location(importStack.element().getResource(), attemptedImport.getMetadata()));
		}
	}

}
