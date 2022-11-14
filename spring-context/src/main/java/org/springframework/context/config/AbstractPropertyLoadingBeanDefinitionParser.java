/*
 * Copyright 2002-2016 the original author or authors.
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

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.StringUtils;

/**
 * Abstract parser for &lt;context:property-.../&gt; elements.
 *
 * @author Juergen Hoeller
 * @author Arjen Poutsma
 * @author Dave Syer
 * @since 2.5.2
 */
abstract class AbstractPropertyLoadingBeanDefinitionParser extends AbstractSingleBeanDefinitionParser {

	@Override
	protected boolean shouldGenerateId() {
		return true;
	}

	/**
	 * 用于解析<context:property-.../>之类的扩展标签的属性解析
	 * @param element       <context:property-.../>标签元素
	 * @param parserContext 解析上下文
	 * @param builder       bean定义构建者
	 */
	@Override
	protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
		//解析location属性
		String location = element.getAttribute("location");
		if (StringUtils.hasLength(location)) {
			//属性文件的路径也支持占位符${..}，但是只能使用environment中的属性源
			location = parserContext.getReaderContext().getEnvironment().resolvePlaceholders(location);
			//根据","拆分为路径数组
			String[] locations = StringUtils.commaDelimitedListToStringArray(location);
			//设置到propertyValues属性中，在创建实例的时候将会被解析为Resource
			builder.addPropertyValue("locations", locations);
		}
		//解析properties-ref属性
		String propertiesRef = element.getAttribute("properties-ref");
		if (StringUtils.hasLength(propertiesRef)) {
			builder.addPropertyReference("properties", propertiesRef);
		}
		//解析file-encoding属性
		String fileEncoding = element.getAttribute("file-encoding");
		if (StringUtils.hasLength(fileEncoding)) {
			builder.addPropertyValue("fileEncoding", fileEncoding);
		}
		//解析order属性
		String order = element.getAttribute("order");
		if (StringUtils.hasLength(order)) {
			builder.addPropertyValue("order", Integer.valueOf(order));
		}
		//解析ignoreResourceNotFound属性
		builder.addPropertyValue("ignoreResourceNotFound",
				Boolean.valueOf(element.getAttribute("ignore-resource-not-found")));
		//解析localOverride属性
		builder.addPropertyValue("localOverride",
				Boolean.valueOf(element.getAttribute("local-override")));
		//设置role属性
		builder.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
	}

}
