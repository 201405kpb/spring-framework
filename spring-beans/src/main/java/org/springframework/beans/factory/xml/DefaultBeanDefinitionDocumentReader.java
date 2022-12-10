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

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * Default implementation of the {@link BeanDefinitionDocumentReader} interface that
 * reads bean definitions according to the "spring-beans" DTD and XSD format
 * (Spring's default XML bean definition format).
 *
 * <p>The structure, elements, and attribute names of the required XML document
 * are hard-coded in this class. (Of course a transform could be run if necessary
 * to produce this format). {@code <beans>} does not need to be the root
 * element of the XML document: this class will parse all bean definition elements
 * in the XML file, regardless of the actual root element.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

	/**
	 * <beans/>标签名常量
	 */
	public static final String NESTED_BEANS_ELEMENT = "beans";

	/**
	 * <alias/>标签名常量
	 */
	public static final String ALIAS_ELEMENT = "alias";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String ALIAS_ATTRIBUTE = "alias";
	/**
	 * <import/>标签名常量
	 */
	public static final String IMPORT_ELEMENT = "import";

	public static final String RESOURCE_ATTRIBUTE = "resource";

	public static final String PROFILE_ATTRIBUTE = "profile";


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private XmlReaderContext readerContext;

	@Nullable
	private BeanDefinitionParserDelegate delegate;


	/**
	 * This implementation parses bean definitions according to the "spring-beans" XSD
	 * (or DTD, historically).
	 * <p>Opens a DOM Document; then initializes the default settings
	 * specified at the {@code <beans/>} level; then parses the contained bean definitions.
	 *
	 * 根据“spring-beans"的XSD（或者DTD）去解析bean definition
	 * 打开一个DOM文档，然后初始化在<beans/>层级上指定的默认设置，然后解析包含在其中的bean definitions
	 */
	@Override
	public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
		this.readerContext = readerContext;
		// 从 xml 根节点开始解析文件
		doRegisterBeanDefinitions(doc.getDocumentElement());
	}

	/**
	 * Return the descriptor for the XML resource that this parser works on.
	 */
	protected final XmlReaderContext getReaderContext() {
		Assert.state(this.readerContext != null, "No XmlReaderContext available");
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor}
	 * to pull the source metadata from the supplied {@link Element}.
	 */
	@Nullable
	protected Object extractSource(Element ele) {
		return getReaderContext().extractSource(ele);
	}


	/**
	 * Register each bean definition within the given root {@code <beans/>} element.
	 * 在给定的根元素对象<beans/>中，注册每一个bean definition
	 */
	@SuppressWarnings("deprecation")  // for Environment.acceptsProfiles(String...)
	protected void doRegisterBeanDefinitions(Element root) {
		// Any nested <beans> elements will cause recursion in this method. In
		// order to propagate and preserve <beans> default-* attributes correctly,
		// keep track of the current (parent) delegate, which may be null. Create
		// the new (child) delegate with a reference to the parent for fallback purposes,
		// then ultimately reset this.delegate back to its original (parent) reference.
		// this behavior emulates a stack of delegates without actually necessitating one.
		// 任何嵌套的＜beans＞元素都将导致此方法中的递归。为了正确传播和保留＜beans＞默认属性，请跟踪当前（父）委托，该委托可能为空。
		// 创建一个新的（子）委托，并引用父委托以备回退，然后最终将此委托重置回其原始（父）引用。此行为模拟一堆代理，而实际上不需要一个。
		BeanDefinitionParserDelegate parent = this.delegate;
		//创建一个新的代理，并初始化一些默认值
		this.delegate = createDelegate(getReaderContext(), root, parent);
		if (this.delegate.isDefaultNamespace(root)) {
			// 这块说的是根节点 <beans ... profile="dev" /> 中的 profile 是否是当前环境需要的，
			// 如果当前环境配置的 profile 不包含此 profile，那就直接 return 了，不对此 <beans /> 解析
			String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
			if (StringUtils.hasText(profileSpec)) {
				String[] specifiedProfiles = StringUtils.tokenizeToStringArray(
						profileSpec, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);
				// We cannot use Profiles.of(...) since profile expressions are not supported
				// in XML config. See SPR-12458 for details.
				if (!getReaderContext().getEnvironment().acceptsProfiles(specifiedProfiles)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Skipped XML bean definition file due to specified profiles [" + profileSpec +
								"] not matching: " + getReaderContext().getResource());
					}
					return;
				}
			}
		}
		//如果有，那么继续解析
		//在开始处理 bean 定义之前的扩展点位。默认实现为空，子类可以重写此方法
		preProcessXml(root);
		assert this.delegate != null;
		//真正的从根元素开始进行bean定义的解析
		parseBeanDefinitions(root, this.delegate);
		//在完成 bean 定义处理后的扩展点位。默认实现为空，子类可以重写此方法
		postProcessXml(root);
		//赋值
		this.delegate = parent;
	}

	/**
	 * 创建bean定义的解析器，解析<beans/>根节点元素的属性
	 *
	 * @param readerContext  readerContext
	 * @param root           根节点元素
	 * @param parentDelegate 父解析器
	 * @return 新创建的delegate
	 */
	protected BeanDefinitionParserDelegate createDelegate(
			XmlReaderContext readerContext, Element root, @Nullable BeanDefinitionParserDelegate parentDelegate) {
		//创建解析器
		BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		//设置解析器默认值，即解析<beans/>根标签的属性
		delegate.initDefaults(root, parentDelegate);
		//返回解析器
		return delegate;
	}

	/**
	 * Parse the elements at the root level in the document:
	 * "import", "alias", "bean".
	 * 从给定的<beans/>根标签元素中读取子标签并解析，注意<beans/>标签可以嵌套
	 * @param root the DOM root element of the document
	 */
	protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
		/*
		 * 如果属于默认命名空间下的标签 <import/>、<alias/>、<bean/>、<beans/>
		 */
		if (delegate.isDefaultNamespace(root)) {
			//获取标签下的所有直接子节点
			NodeList nl = root.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);
				//标签之间的空白换行符号/n也会算作一个Node节点 -> DeferredTextImpl，标签之间被注释的语句也会算作一个Node节点 -> DeferredCommentImpl
				//这里需要筛选出真正需要被解析的标签元素节点，即Element -> DeferredElementNSImpl，因此XML中标签之间的注释在一定程度上也会增加遍历以及判断成本
				if (node instanceof Element ele) {
					//属于默认命名空间下的标签 <import/>、<alias/>、<bean/>、<beans/>
					if (delegate.isDefaultNamespace(ele)) {
						parseDefaultElement(ele, delegate);
					}
					//其他扩展标签，mvc/>、<task/>、<context/>、<aop/>等。
					else {
						delegate.parseCustomElement(ele);
					}
				}
			}
		}
		/*
		 * 其他命名空间的扩展标签，<mvc/>、<task/>、<context/>、<aop/>等。
		 */
		else {
			delegate.parseCustomElement(root);
		}
	}

	/**
	 * 解析beans根标签下的默认命名空间（xmlns="<a href="http://www.springframework.org/schema/beans">...</a>"）下的标签
	 * 就是四个标签: <import/>、<alias/>、<bean/>、<beans/>
	 * @param ele
	 * @param delegate
	 */
	private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
		// 处理 <import /> 标签
		if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
			importBeanDefinitionResource(ele);
		}
		// 处理 <alias /> 标签
		else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
			processAliasRegistration(ele);
		}
		// 处理 <bean /> 标签
		else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
			processBeanDefinition(ele, delegate);
		}
		// 处理 <beans /> 标签
		else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
			// recurse
			doRegisterBeanDefinitions(ele);
		}
	}

	/**
	 * Parse an "import" element and load the bean definitions
	 * from the given resource into the bean factory.
	 * 解析<import/>标签，将给定资源的 bean 定义加载到 bean 工厂。
	 */
	protected void importBeanDefinitionResource(Element ele) {
		//获取resource属性值
		String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
		//如果没有为null或者""或者空白字符，那么将抛出异常
		if (!StringUtils.hasText(location)) {
			getReaderContext().error("Resource location must not be empty", ele);
			return;
		}
		/*
		 * 使用环境变量对象的resolveRequiredPlaceholders方法来解析资源路径字符串，这说明资源路径也可以使用占位符${..:..}
		 * resolvePlaceholders方法将会使用严格模式，没有默认值的无法解析的占位符则抛出IllegalArgumentException异常
		 * 在setConfigLocations部分中我们就学过了该方法
		 */
		location = getReaderContext().getEnvironment().resolveRequiredPlaceholders(location);

		Set<Resource> actualResources = new LinkedHashSet<>(4);

		// 判断该路径是绝对的还是相对的 URI
		boolean absoluteLocation = false;
		try {
			//如果是以"classpath*:"、"classpath:"开头或者可以据此创立一个URL对象，或者此URI是一个绝对路径，那么就是绝对URI
			absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
		} catch (URISyntaxException ex) {
			// cannot convert to an URI, considering the location relative
			// unless it is the well-known Spring prefix "classpath*:"
		}
		/*绝对路径*/
		if (absoluteLocation) {
			try {
				//加载指定路径下面的配置文件
				int importCount = getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from URL location [" + location + "]");
				}
			} catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from URL location [" + location + "]", ele, ex);
			}
		}
		/*相对路径*/
		else {
			// No URL -> considering resource location as relative to the current file.
			try {
				int importCount;
				//尝试转换为单个资源
				Resource relativeResource = getReaderContext().getResource().createRelative(location);
				//此资源是否存在确定的一个文件，如果使用了Ant通配符，表示匹配多个资源文件，那么不会存在
				if (relativeResource.exists()) {
					//如果是确定的一个文件，那么解析该文件，这个loadBeanDefinitions方法在前面就讲过了
					importCount = getReaderContext().getReader().loadBeanDefinitions(relativeResource);
					actualResources.add(relativeResource);
				} else {
					//获取基础文件路径，也就是<import/>标签所在文件路径
					String baseLocation = getReaderContext().getResource().getURL().toString();
					//根据当前<import/>标签所在文件路径将相对路径转为绝对路径然后再进行解析
					importCount = getReaderContext().getReader().loadBeanDefinitions(
							StringUtils.applyRelativePath(baseLocation, location), actualResources);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from relative location [" + location + "]");
				}
			} catch (IOException ex) {
				getReaderContext().error("Failed to resolve current resource location", ele, ex);
			} catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from relative location [" + location + "]", ele, ex);
			}
		}
		//过去解析的所有Resource资源的数组
		Resource[] actResArray = actualResources.toArray(new Resource[0]);
		//发布事件
		getReaderContext().fireImportProcessed(location, actResArray, extractSource(ele));
	}

	/**
	 * Process the given alias element, registering the alias with the registry.
	 * 解析给定的<alias/>标签，在注册表中注册别名映射。
	 */
	protected void processAliasRegistration(Element ele) {
		//获取name和alias属性
		String name = ele.getAttribute(NAME_ATTRIBUTE);
		String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
		boolean valid = true;
		//name不能为null、""、空白字符，否则抛出异常："Name must not be empty"
		if (!StringUtils.hasText(name)) {
			getReaderContext().error("Name must not be empty", ele);
			valid = false;
		}
		//alias不能为null、""、空白字符，否则抛出异常："Alias must not be empty"
		if (!StringUtils.hasText(alias)) {
			getReaderContext().error("Alias must not be empty", ele);
			valid = false;
		}
		if (valid) {
			try {
				/*
				 * 调用registerAlias方法，将beanName和aliases中的每一个别名注册到registry的缓存中。
				 * 这个缓存实际上就是上下文容器内部的DefaultListableBeanFactory的父类SimpleAliasRegistry注册表实例中的aliasMap缓存。
				 * 这个方法我们在解析<bean/>标签的processBeanDefinition中就讲过了，所以说
				 * 在<bean/>标签中设置的别名和<alias/>标签设置的别名都是存放在同一个缓存中的
				 */
				getReaderContext().getRegistry().registerAlias(name, alias);
			} catch (Exception ex) {
				getReaderContext().error("Failed to register alias '" + alias +
						"' for bean with name '" + name + "'", ele, ex);
			}
			//发布事件
			getReaderContext().fireAliasRegistered(name, alias, extractSource(ele));
		}
	}

	/**
	 * Process the given bean element, parsing the bean definition
	 * and registering it with the registry.
	 * 处理给定的<bean/>标签，解析bean定义并将其注册到注册表
	 */
	protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
		// 将 <bean /> 节点中的信息提取出来，然后封装到一个 BeanDefinitionHolder 中
		BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
		if (bdHolder != null) {
			// 如果有自定义属性的话，进行相应的解析
			bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);
			try {
				// Register the final decorated instance.
				BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to register bean definition with name '" +
						bdHolder.getBeanName() + "'", ele, ex);
			}
			// Send registration event.
			// 注册完成后，发送事件
			getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
		}
	}


	/**
	 * Allow the XML to be extensible by processing any custom element types first,
	 * before we start to process the bean definitions. This method is a natural
	 * extension point for any other custom pre-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * 在开始处理 bean 定义之前的扩展点位。默认实现为空，子类可以重写此方法
	 * @see #getReaderContext()
	 */
	protected void preProcessXml(Element root) {
	}

	/**
	 * Allow the XML to be extensible by processing any custom element types last,
	 * after we finished processing the bean definitions. This method is a natural
	 * extension point for any other custom post-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * 在完成 bean 定义处理后的扩展点位。默认实现为空，子类可以重写此方法
	 * @see #getReaderContext()
	 */
	protected void postProcessXml(Element root) {
	}

}
