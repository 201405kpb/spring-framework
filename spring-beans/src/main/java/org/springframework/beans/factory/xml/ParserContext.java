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

package org.springframework.beans.factory.xml;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.ComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.lang.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Context that gets passed along a bean definition parsing process,
 * encapsulating all relevant configuration as well as state.
 * Nested inside an {@link XmlReaderContext}.
 * 通过bean定义解析过程传递的上下文，封装所有相关配置和状态。
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @see XmlReaderContext
 * @see BeanDefinitionParserDelegate
 * @since 2.0
 */
public final class ParserContext {

	private final XmlReaderContext readerContext;

	private final BeanDefinitionParserDelegate delegate;

	@Nullable
	private BeanDefinition containingBeanDefinition;

	//存放一系列的组件bean定义,这是一个ArrayDeque集合，可以模拟栈
	private final Deque<CompositeComponentDefinition> containingComponents = new ArrayDeque<>();


	public ParserContext(XmlReaderContext readerContext, BeanDefinitionParserDelegate delegate) {
		this.readerContext = readerContext;
		this.delegate = delegate;
	}

	public ParserContext(XmlReaderContext readerContext, BeanDefinitionParserDelegate delegate,
						 @Nullable BeanDefinition containingBeanDefinition) {

		this.readerContext = readerContext;
		this.delegate = delegate;
		this.containingBeanDefinition = containingBeanDefinition;
	}


	public XmlReaderContext getReaderContext() {
		return this.readerContext;
	}

	public BeanDefinitionRegistry getRegistry() {
		return this.readerContext.getRegistry();
	}

	public BeanDefinitionParserDelegate getDelegate() {
		return this.delegate;
	}

	@Nullable
	public BeanDefinition getContainingBeanDefinition() {
		return this.containingBeanDefinition;
	}

	public boolean isNested() {
		return (this.containingBeanDefinition != null);
	}

	public boolean isDefaultLazyInit() {
		return BeanDefinitionParserDelegate.TRUE_VALUE.equals(this.delegate.getDefaults().getLazyInit());
	}

	@Nullable
	public Object extractSource(Object sourceCandidate) {
		return this.readerContext.extractSource(sourceCandidate);
	}

	@Nullable
	public CompositeComponentDefinition getContainingComponent() {
		return this.containingComponents.peek();
	}

	// 存入containingComponents栈顶
	public void pushContainingComponent(CompositeComponentDefinition containingComponent) {
		this.containingComponents.push(containingComponent);
	}

	// 栈顶元素出栈
	public CompositeComponentDefinition popContainingComponent() {
		return this.containingComponents.pop();
	}

	// 栈顶元素出栈并注册
	public void popAndRegisterContainingComponent() {

		//注册组件
		registerComponent(popContainingComponent());
	}

	/**
	 * 注册组件，并不是注册到注册表中……
	 */
	public void registerComponent(ComponentDefinition component) {
		//获取但不移除最新栈顶元素
		CompositeComponentDefinition containingComponent = getContainingComponent();
		//如果栈顶元素不为null，那么当前组件加入到栈顶元素的内部集合中
		if (containingComponent != null) {
			containingComponent.addNestedComponent(component);
		}
		//否则，通过readerContext发布组件注册事件，默认也是个空方法，啥都没干……
		else {
			this.readerContext.fireComponentRegistered(component);
		}
	}

	// 获取但不移除最新栈顶元素
	public void registerBeanComponent(BeanComponentDefinition component) {
		BeanDefinitionReaderUtils.registerBeanDefinition(component, getRegistry());
		registerComponent(component);
	}

}
