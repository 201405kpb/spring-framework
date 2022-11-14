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

package org.springframework.aop.config;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;

/**
 * {@link BeanDefinitionParser} for the {@code aspectj-autoproxy} tag,
 * enabling the automatic application of @AspectJ-style aspects found in
 * the {@link org.springframework.beans.factory.BeanFactory}.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 2.0
 */
class AspectJAutoProxyBeanDefinitionParser implements BeanDefinitionParser {

	/**
	 *  解析<aop:aspectj-autoproxy/>标签，开启AOP注解支持
	 * @param element the element that is to be parsed into one or more {@link BeanDefinition BeanDefinitions}
	 *  <aop:aspectj-autoproxy/>标签元素
	 * @param parserContext the object encapsulating the current state of the parsing process;
	 * provides access to a {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
	 * 解析上下文
	 * @return 默认null
	 */
	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		/*
		 * 1 尝试解析<aop:aspectj-autoproxy/>标签的属性，并且注册或者升级一个名为"org.springframework.aop.config.internalAutoProxyCreator"
		 * 类型为AnnotationAwareAspectJAutoProxyCreator的自动代理创建者的bean定义
		 */
		AopNamespaceUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(parserContext, element);
		/*
		 * 2 解析<aop:include/>子标签，扩展自动代理创建者的bean定义
		 * 该标签用于指定一些模式从而根据beanName筛选切面类，这个标签很少被使用
		 */
		extendBeanDefinition(element, parserContext);
		return null;
	}

	/**
	 * 解析<aop:include/>子标签，扩展bean定义
	 * @param element  <aop:aspectj-autoproxy/>标签元素
	 * @param parserContext 解析上下文
	 */
	private void extendBeanDefinition(Element element, ParserContext parserContext) {
		//获取在前面注册的自动代理创建者的bean定义
		BeanDefinition beanDef =
				parserContext.getRegistry().getBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME);
		//如果当前<aop:aspectj-autoproxy/>标签具有子节点
		if (element.hasChildNodes()) {
			//添加包括的模式
			addIncludePatterns(element, parserContext, beanDef);
		}
	}

	/**
	 * 解析<aop:include/>子标签，扩展bean定义
	 *
	 * @param element       <aop:aspectj-autoproxy/>标签元素
	 * @param parserContext 解析上下文
	 * @param beanDef       自动代理创建者的bean定义
	 */
	private void addIncludePatterns(Element element, ParserContext parserContext, BeanDefinition beanDef) {
		ManagedList<TypedStringValue> includePatterns = new ManagedList<>();
		NodeList childNodes = element.getChildNodes();
		//遍历子节点
		for (int i = 0; i < childNodes.getLength(); i++) {
			Node node = childNodes.item(i);
			//处理标签节点
			if (node instanceof Element) {
				Element includeElement = (Element) node;
				//获取name属性值，封装称为一个TypedStringValue
				TypedStringValue valueHolder = new TypedStringValue(includeElement.getAttribute("name"));
				//设置源，当前<aop:include/>子标签
				valueHolder.setSource(parserContext.extractSource(includeElement));
				//加入到includePatterns中
				includePatterns.add(valueHolder);
			}
		}
		//如果includePatterns不为空
		if (!includePatterns.isEmpty()) {
			//设置源，当前<aop:aspectj-autoproxy/>标签
			includePatterns.setSource(parserContext.extractSource(element));
			//设置到自动代理创建者的bean定义的属性中
			//属性名为includePatterns，值就是includePatterns集合
			beanDef.getPropertyValues().add("includePatterns", includePatterns);
		}
	}

}
