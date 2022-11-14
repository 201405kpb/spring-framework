/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.context.config;

import org.w3c.dom.Element;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.util.StringUtils;

/**
 * Parser for the {@code <context:property-placeholder/>} element.
 *
 * @author Juergen Hoeller
 * @author Dave Syer
 * @author Chris Beams
 * @since 2.5
 */
class PropertyPlaceholderBeanDefinitionParser extends AbstractPropertyLoadingBeanDefinitionParser {

	private static final String SYSTEM_PROPERTIES_MODE_ATTRIBUTE = "system-properties-mode";

	private static final String SYSTEM_PROPERTIES_MODE_DEFAULT = "ENVIRONMENT";


	@Override
	@SuppressWarnings("deprecation")
	protected Class<?> getBeanClass(Element element) {
		// As of Spring 3.1, the default value of system-properties-mode has changed from
		// 'FALLBACK' to 'ENVIRONMENT'. This latter value indicates that resolution of
		// placeholders against system properties is a function of the Environment and
		// its current set of PropertySources.
		/*
		 * 自Spring 3.1 开始，system-properties-mode的属性的默认值从'FALLBACK' 变成 'ENVIRONMENT'
		 * 'ENVIRONMENT'表示占位符对系统属性的解析是环境及其当前属性源集的函数。
		 */
		if (SYSTEM_PROPERTIES_MODE_DEFAULT.equals(element.getAttribute(SYSTEM_PROPERTIES_MODE_ATTRIBUTE))) {
			/*
			 * 返回PropertySourcesPlaceholderConfigurer.class，该类自Spring 3.1 开始用于替代PropertyPlaceholderConfigurer
			 * 因为该类更加灵活，支持Environment本地环境变量属性源、外部配置属性源、以及Spring 3.1的PropertySource属性源机制
			 *
			 * 用来解析替换bean定义内的属性值和@Resource、@Value等注解的值中的${..:..}占位符。
			 */
			return PropertySourcesPlaceholderConfigurer.class;
		}

		// The user has explicitly specified a value for system-properties-mode: revert to
		// PropertyPlaceholderConfigurer to ensure backward compatibility with 3.0 and earlier.
		// This is deprecated; to be removed along with PropertyPlaceholderConfigurer itself.
		/*
		 * 仅仅为了兼容Spring 3.0以及更早的版本，该类实现以及PropertyPlaceholderConfigurer都被标记为废弃
		 * 我们不应该继续使用该类，所以现在有些文章或者教程中还在使用这个类是不明智的
		 */
		return org.springframework.beans.factory.config.PropertyPlaceholderConfigurer.class;
	}

	/**
	 * PropertyPlaceholderBeanDefinitionParser的方法
	 * <p>
	 * 解析自有的属性
	 *
	 * @param element       <context:property-placeholder/>标签元素
	 * @param parserContext 解析上下文
	 * @param builder       bean定义构建者
	 */
	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
		//调用父类AbstractPropertyLoadingBeanDefinitionParser的方法
		super.doParse(element, parserContext, builder);
		//解析ignore-unresolvable属性，表示是否忽略没有默认值的无法解析的占位符，默认false，即不能忽略，设置为ignoreUnresolvablePlaceholders属性的值
		builder.addPropertyValue("ignoreUnresolvablePlaceholders",
				Boolean.valueOf(element.getAttribute("ignore-unresolvable")));
		//解析system-properties-mode属性，默认ENVIRONMENT
		String systemPropertiesModeName = element.getAttribute(SYSTEM_PROPERTIES_MODE_ATTRIBUTE);
		if (StringUtils.hasLength(systemPropertiesModeName) &&
				!systemPropertiesModeName.equals(SYSTEM_PROPERTIES_MODE_DEFAULT)) {
			builder.addPropertyValue("systemPropertiesModeName", "SYSTEM_PROPERTIES_MODE_" + systemPropertiesModeName);
		}
		//解析value-separator属性
		if (element.hasAttribute("value-separator")) {
			builder.addPropertyValue("valueSeparator", element.getAttribute("value-separator"));
		}
		//解析trim-values属性
		if (element.hasAttribute("trim-values")) {
			builder.addPropertyValue("trimValues", element.getAttribute("trim-values"));
		}
		//解析null-value属性
		if (element.hasAttribute("null-value")) {
			builder.addPropertyValue("nullValue", element.getAttribute("null-value"));
		}
	}

}
