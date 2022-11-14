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

package org.springframework.context.support;

import java.io.IOException;
import java.util.Properties;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PlaceholderConfigurerSupport;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringValueResolver;

/**
 * Specialization of {@link PlaceholderConfigurerSupport} that resolves ${...} placeholders
 * within bean definition property values and {@code @Value} annotations against the current
 * Spring {@link Environment} and its set of {@link PropertySources}.
 *
 * <p>This class is designed as a general replacement for {@code PropertyPlaceholderConfigurer}.
 * It is used by default to support the {@code property-placeholder} element in working against
 * the spring-context-3.1 or higher XSD; whereas, spring-context versions &lt;= 3.0 default to
 * {@code PropertyPlaceholderConfigurer} to ensure backward compatibility. See the spring-context
 * XSD documentation for complete details.
 *
 * <p>Any local properties (e.g. those added via {@link #setProperties}, {@link #setLocations}
 * et al.) are added as a {@code PropertySource}. Search precedence of local properties is
 * based on the value of the {@link #setLocalOverride localOverride} property, which is by
 * default {@code false} meaning that local properties are to be searched last, after all
 * environment property sources.
 *
 * <p>See {@link org.springframework.core.env.ConfigurableEnvironment} and related javadocs
 * for details on manipulating environment property sources.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @see org.springframework.core.env.ConfigurableEnvironment
 * @see org.springframework.beans.factory.config.PlaceholderConfigurerSupport
 * @see org.springframework.beans.factory.config.PropertyPlaceholderConfigurer
 */
public class PropertySourcesPlaceholderConfigurer extends PlaceholderConfigurerSupport implements EnvironmentAware {

	/**
	 * {@value} is the name given to the {@link PropertySource} for the set of
	 * {@linkplain #mergeProperties() merged properties} supplied to this configurer.
	 */
	public static final String LOCAL_PROPERTIES_PROPERTY_SOURCE_NAME = "localProperties";

	/**
	 * {@value} is the name given to the {@link PropertySource} that wraps the
	 * {@linkplain #setEnvironment environment} supplied to this configurer.
	 */
	public static final String ENVIRONMENT_PROPERTIES_PROPERTY_SOURCE_NAME = "environmentProperties";


	@Nullable
	private MutablePropertySources propertySources;

	@Nullable
	private PropertySources appliedPropertySources;

	@Nullable
	private Environment environment;


	/**
	 * Customize the set of {@link PropertySources} to be used by this configurer.
	 * <p>Setting this property indicates that environment property sources and
	 * local properties should be ignored.
	 * @see #postProcessBeanFactory
	 */
	public void setPropertySources(PropertySources propertySources) {
		this.propertySources = new MutablePropertySources(propertySources);
	}

	/**
	 * {@code PropertySources} from the given {@link Environment}
	 * will be searched when replacing ${...} placeholders.
	 * @see #setPropertySources
	 * @see #postProcessBeanFactory
	 */
	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}


	/**
	 * Processing occurs by replacing ${...} placeholders in bean definitions by resolving each
	 * against this configurer's set of {@link PropertySources}, which includes:
	 * <ul>
	 * <li>all {@linkplain org.springframework.core.env.ConfigurableEnvironment#getPropertySources
	 * environment property sources}, if an {@code Environment} {@linkplain #setEnvironment is present}
	 * <li>{@linkplain #mergeProperties merged local properties}, if {@linkplain #setLocation any}
	 * {@linkplain #setLocations have} {@linkplain #setProperties been}
	 * {@linkplain #setPropertiesArray specified}
	 * <li>any property sources set by calling {@link #setPropertySources}
	 * </ul>
	 * <p>If {@link #setPropertySources} is called, <strong>environment and local properties will be
	 * ignored</strong>. This method is designed to give the user fine-grained control over property
	 * sources, and once set, the configurer makes no assumptions about adding additional sources.
	 *
	 * 配置后续用于替换${...}占位符的属性源到PropertySources中，属性源来自：
	 * 1 所有的Environment环境变量中的属性源，比如systemEnvironment和systemProperties，setEnvironment
	 * 2 本地配置的属性文件引入的属性源头，mergeProperties、setLocation、setLocations、setProperties
	 * 3 通过PropertySource引入的属性源文件，setPropertySources
	 * <p>如果setPropertySources方法已被调用并设置了值，那么所有的environment和本地属性源都被将忽略
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		/*
		 * 如果自定义的属性源为null，那么根据environment和本地配置属性构建属性源，默认就是null
		 */
		if (this.propertySources == null) {
			//新建可变的属性源集合，内部可以出有多个属性源
			this.propertySources = new MutablePropertySources();
			//如果environment不为null
			if (this.environment != null) {
				PropertyResolver propertyResolver = this.environment;
				// If the ignoreUnresolvablePlaceholders flag is set to true, we have to create a
				// local PropertyResolver to enforce that setting, since the Environment is most
				// likely not configured with ignoreUnresolvablePlaceholders set to true.
				// See https://github.com/spring-projects/spring-framework/issues/27947
				if (this.ignoreUnresolvablePlaceholders &&
						(this.environment instanceof ConfigurableEnvironment configurableEnvironment)) {
					PropertySourcesPropertyResolver resolver =
							new PropertySourcesPropertyResolver(configurableEnvironment.getPropertySources());
					resolver.setIgnoreUnresolvableNestedPlaceholders(true);
					propertyResolver = resolver;
				}
				PropertyResolver propertyResolverToUse = propertyResolver;
				//根据name=environmentProperties以及environment，构建一个PropertySource属性源并加入到propertySources集合尾部
				this.propertySources.addLast(
					new PropertySource<>(ENVIRONMENT_PROPERTIES_PROPERTY_SOURCE_NAME, this.environment) {
						@Override
						@Nullable
						public String getProperty(String key) {
							return propertyResolverToUse.getProperty(key);
						}
					}
				);
			}
			try {
				//根据name=localProperties以及通过mergeProperties方法加载的所有本地配置的属性源，构建一个PropertiesPropertySource属性源
				PropertySource<?> localPropertySource =
						new PropertiesPropertySource(LOCAL_PROPERTIES_PROPERTY_SOURCE_NAME, mergeProperties());
				//默认false
				if (this.localOverride) {
					this.propertySources.addFirst(localPropertySource);
				}
				else {
					//将加载的所有本地配置的属性源加入到propertySources集合尾部
					this.propertySources.addLast(localPropertySource);
				}
			}
			catch (IOException ex) {
				throw new BeanInitializationException("Could not load properties", ex);
			}
		}
		/*
		 * 根据当前的propertySources新建一个PropertySourcesPropertyResolver
		 * 调用processProperties方法，访问给定 bean 工厂中的每个 bean 定义，并尝试将内部的${...}占位符替换为来自给定propertySources属性源的值
		 */
		processProperties(beanFactory, new PropertySourcesPropertyResolver(this.propertySources));
		//设置appliedPropertySources属性
		this.appliedPropertySources = this.propertySources;
	}

	/**
	 * Visit each bean definition in the given bean factory and attempt to replace ${...} property
	 * placeholders with values from the given properties.
	 * 访问给定 bean 工厂中的每个 bean 定义，并尝试将内部的${...}占位符替换为来自给定属性源的值。
	 */
	protected void processProperties(ConfigurableListableBeanFactory beanFactoryToProcess,
									 final ConfigurablePropertyResolver propertyResolver) throws BeansException {
		//设置占位符的格式，可以自定义
		propertyResolver.setPlaceholderPrefix(this.placeholderPrefix);
		propertyResolver.setPlaceholderSuffix(this.placeholderSuffix);
		propertyResolver.setValueSeparator(this.valueSeparator);
		/*
		 * 创建StringValueResolver的lambda对象，它的resolveStringValue方法实际上就是调用propertyResolver的方法
		 * 如果允许忽略无法解析的没有默认值的占位符，那么调用resolvePlaceholders方法，否则调用resolveRequiredPlaceholders方法
		 * 这两个方法我们在IoC容器第一部分setConfigLocations部分的源码中都讲过了
		 *
		 * 后续就是通过调用该解析器对戏那个来解析占位符的
		 */
		StringValueResolver valueResolver = strVal -> {
			String resolved = (this.ignoreUnresolvablePlaceholders ?
					propertyResolver.resolvePlaceholders(strVal) :
					propertyResolver.resolveRequiredPlaceholders(strVal));
			//在返回解析结果在前是否应该去除前后空白字符，对应着<context:property-placeholder/>的trim-values属性
			//没有XML的默认值，属性默认为false
			if (this.trimValues) {
				resolved = resolved.trim();
			}
			//属性值是否为nullValue，如果是那么返回null，否则直接返回解析后的值
			return (resolved.equals(this.nullValue) ? null : resolved);
		};
		/*
		 * 调用父类PlaceholderConfigurerSupport的方法，检查所有bean定义，替换占位符
		 */
		doProcessProperties(beanFactoryToProcess, valueResolver);
	}

	/**
	 * Implemented for compatibility with
	 * {@link org.springframework.beans.factory.config.PlaceholderConfigurerSupport}.
	 * @throws UnsupportedOperationException in this implementation
	 * @deprecated in favor of
	 * {@link #processProperties(ConfigurableListableBeanFactory, ConfigurablePropertyResolver)}
	 */
	@Override
	@Deprecated
	protected void processProperties(ConfigurableListableBeanFactory beanFactory, Properties props) {
		throw new UnsupportedOperationException(
				"Call processProperties(ConfigurableListableBeanFactory, ConfigurablePropertyResolver) instead");
	}

	/**
	 * Return the property sources that were actually applied during
	 * {@link #postProcessBeanFactory(ConfigurableListableBeanFactory) post-processing}.
	 * @return the property sources that were applied
	 * @throws IllegalStateException if the property sources have not yet been applied
	 * @since 4.0
	 */
	public PropertySources getAppliedPropertySources() throws IllegalStateException {
		Assert.state(this.appliedPropertySources != null, "PropertySources have not yet been applied");
		return this.appliedPropertySources;
	}

}
