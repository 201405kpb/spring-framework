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

package org.springframework.beans.factory.xml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.BeanMetadataAttribute;
import org.springframework.beans.BeanMetadataAttributeAccessor;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.parsing.BeanEntry;
import org.springframework.beans.factory.parsing.ConstructorArgumentEntry;
import org.springframework.beans.factory.parsing.ParseState;
import org.springframework.beans.factory.parsing.PropertyEntry;
import org.springframework.beans.factory.parsing.QualifierEntry;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionDefaults;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.LookupOverride;
import org.springframework.beans.factory.support.ManagedArray;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.ManagedProperties;
import org.springframework.beans.factory.support.ManagedSet;
import org.springframework.beans.factory.support.MethodOverrides;
import org.springframework.beans.factory.support.ReplaceOverride;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;

/**
 * Stateful delegate class used to parse XML bean definitions.
 * Intended for use by both the main parser and any extension
 * {@link BeanDefinitionParser BeanDefinitionParsers} or
 * {@link BeanDefinitionDecorator BeanDefinitionDecorators}.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Mark Fisher
 * @author Gary Russell
 * @see ParserContext
 * @see DefaultBeanDefinitionDocumentReader
 * @since 2.0
 */
public class BeanDefinitionParserDelegate {

	public static final String BEANS_NAMESPACE_URI = "http://www.springframework.org/schema/beans";

	public static final String MULTI_VALUE_ATTRIBUTE_DELIMITERS = ",; ";

	/**
	 * Value of a T/F attribute that represents true.
	 * Anything else represents false.
	 */
	public static final String TRUE_VALUE = "true";

	public static final String FALSE_VALUE = "false";

	public static final String DEFAULT_VALUE = "default";

	public static final String DESCRIPTION_ELEMENT = "description";

	public static final String AUTOWIRE_NO_VALUE = "no";

	public static final String AUTOWIRE_BY_NAME_VALUE = "byName";

	public static final String AUTOWIRE_BY_TYPE_VALUE = "byType";

	public static final String AUTOWIRE_CONSTRUCTOR_VALUE = "constructor";

	public static final String AUTOWIRE_AUTODETECT_VALUE = "autodetect";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String BEAN_ELEMENT = "bean";

	public static final String META_ELEMENT = "meta";

	public static final String ID_ATTRIBUTE = "id";

	public static final String PARENT_ATTRIBUTE = "parent";

	public static final String CLASS_ATTRIBUTE = "class";

	public static final String ABSTRACT_ATTRIBUTE = "abstract";

	public static final String SCOPE_ATTRIBUTE = "scope";

	private static final String SINGLETON_ATTRIBUTE = "singleton";

	public static final String LAZY_INIT_ATTRIBUTE = "lazy-init";

	public static final String AUTOWIRE_ATTRIBUTE = "autowire";

	public static final String AUTOWIRE_CANDIDATE_ATTRIBUTE = "autowire-candidate";

	public static final String PRIMARY_ATTRIBUTE = "primary";

	public static final String DEPENDS_ON_ATTRIBUTE = "depends-on";

	public static final String INIT_METHOD_ATTRIBUTE = "init-method";

	public static final String DESTROY_METHOD_ATTRIBUTE = "destroy-method";

	public static final String FACTORY_METHOD_ATTRIBUTE = "factory-method";

	public static final String FACTORY_BEAN_ATTRIBUTE = "factory-bean";

	public static final String CONSTRUCTOR_ARG_ELEMENT = "constructor-arg";

	public static final String INDEX_ATTRIBUTE = "index";

	public static final String TYPE_ATTRIBUTE = "type";

	public static final String VALUE_TYPE_ATTRIBUTE = "value-type";

	public static final String KEY_TYPE_ATTRIBUTE = "key-type";

	public static final String PROPERTY_ELEMENT = "property";

	public static final String REF_ATTRIBUTE = "ref";

	public static final String VALUE_ATTRIBUTE = "value";

	public static final String LOOKUP_METHOD_ELEMENT = "lookup-method";

	public static final String REPLACED_METHOD_ELEMENT = "replaced-method";

	public static final String REPLACER_ATTRIBUTE = "replacer";

	public static final String ARG_TYPE_ELEMENT = "arg-type";

	public static final String ARG_TYPE_MATCH_ATTRIBUTE = "match";

	public static final String REF_ELEMENT = "ref";

	public static final String IDREF_ELEMENT = "idref";

	public static final String BEAN_REF_ATTRIBUTE = "bean";

	public static final String PARENT_REF_ATTRIBUTE = "parent";

	public static final String VALUE_ELEMENT = "value";

	public static final String NULL_ELEMENT = "null";

	public static final String ARRAY_ELEMENT = "array";

	public static final String LIST_ELEMENT = "list";

	public static final String SET_ELEMENT = "set";

	public static final String MAP_ELEMENT = "map";

	public static final String ENTRY_ELEMENT = "entry";

	public static final String KEY_ELEMENT = "key";

	public static final String KEY_ATTRIBUTE = "key";

	public static final String KEY_REF_ATTRIBUTE = "key-ref";

	public static final String VALUE_REF_ATTRIBUTE = "value-ref";

	public static final String PROPS_ELEMENT = "props";

	public static final String PROP_ELEMENT = "prop";

	public static final String MERGE_ATTRIBUTE = "merge";

	public static final String QUALIFIER_ELEMENT = "qualifier";

	public static final String QUALIFIER_ATTRIBUTE_ELEMENT = "attribute";

	public static final String DEFAULT_LAZY_INIT_ATTRIBUTE = "default-lazy-init";

	public static final String DEFAULT_MERGE_ATTRIBUTE = "default-merge";

	public static final String DEFAULT_AUTOWIRE_ATTRIBUTE = "default-autowire";

	public static final String DEFAULT_AUTOWIRE_CANDIDATES_ATTRIBUTE = "default-autowire-candidates";

	public static final String DEFAULT_INIT_METHOD_ATTRIBUTE = "default-init-method";

	public static final String DEFAULT_DESTROY_METHOD_ATTRIBUTE = "default-destroy-method";


	protected final Log logger = LogFactory.getLog(getClass());

	private final XmlReaderContext readerContext;

	private final DocumentDefaultsDefinition defaults = new DocumentDefaultsDefinition();

	private final ParseState parseState = new ParseState();

	/**
	 * Stores all used bean names so we can enforce uniqueness on a per
	 * beans-element basis. Duplicate bean ids/names may not exist within the
	 * same level of beans element nesting, but may be duplicated across levels.
	 */
	private final Set<String> usedNames = new HashSet<>();


	/**
	 * Create a new BeanDefinitionParserDelegate associated with the supplied
	 * {@link XmlReaderContext}.
	 */
	public BeanDefinitionParserDelegate(XmlReaderContext readerContext) {
		Assert.notNull(readerContext, "XmlReaderContext must not be null");
		this.readerContext = readerContext;
	}


	/**
	 * Get the {@link XmlReaderContext} associated with this helper instance.
	 */
	public final XmlReaderContext getReaderContext() {
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor}
	 * to pull the source metadata from the supplied {@link Element}.
	 */
	@Nullable
	protected Object extractSource(Element ele) {
		return this.readerContext.extractSource(ele);
	}

	/**
	 * Report an error with the given message for the given source element.
	 */
	protected void error(String message, Node source) {
		this.readerContext.error(message, source, this.parseState.snapshot());
	}

	/**
	 * Report an error with the given message for the given source element.
	 */
	protected void error(String message, Element source) {
		this.readerContext.error(message, source, this.parseState.snapshot());
	}

	/**
	 * Report an error with the given message for the given source element.
	 */
	protected void error(String message, Element source, Throwable cause) {
		this.readerContext.error(message, source, this.parseState.snapshot(), cause);
	}


	/**
	 * Initialize the default settings assuming a {@code null} parent delegate.
	 */
	public void initDefaults(Element root) {
		initDefaults(root, null);
	}

	/**
	 * Initialize the default lazy-init, autowire, dependency check settings,
	 * init-method, destroy-method and merge settings. Support nested 'beans'
	 * element use cases by falling back to the given parent in case the
	 * defaults are not explicitly set locally.
	 * 初始化默认的初始化<beans/>根标签的属性，支持继承父<beans/>根标签的属性
	 *
	 * @see #populateDefaults(DocumentDefaultsDefinition, DocumentDefaultsDefinition, org.w3c.dom.Element)
	 * @see #getDefaults()
	 */
	public void initDefaults(Element root, @Nullable BeanDefinitionParserDelegate parent) {
		//重要方法，初始化<beans/>根标签的属性，支持继承父<beans/>根标签的属性
		populateDefaults(this.defaults, (parent != null ? parent.defaults : null), root);
		//默认注册事件回调，默认eventListener为EmptyReaderEventListener，它的方法都是空实现，留给子类重写
		this.readerContext.fireDefaultsRegistered(this.defaults);
	}


	/**
	 * Populate the given DocumentDefaultsDefinition instance with the default lazy-init,
	 * autowire, dependency check settings, init-method, destroy-method and merge settings.
	 * Support nested 'beans' element use cases by falling back to {@code parentDefaults}
	 * in case the defaults are not explicitly set locally.
	 * 初始化<beans/>根标签的属性，支持继承父<beans/>根标签的属性
	 *
	 * @param defaults       the defaults to populate
	 *                       当前<beans/>根标签的默认值要保存的地方
	 * @param parentDefaults the parent BeanDefinitionParserDelegate (if any) defaults to fall back to
	 *                       父<beans/>根标签的默认值
	 * @param root           the root element of the current bean definition document (or nested beans element)
	 *                       当前<beans/>根标签元素
	 */
	protected void populateDefaults(DocumentDefaultsDefinition defaults, @Nullable DocumentDefaultsDefinition parentDefaults, Element root) {
		//获取当前<beans/>根标签的default-lazy-init属性值lazyInit
		String lazyInit = root.getAttribute(DEFAULT_LAZY_INIT_ATTRIBUTE);
		//如果是默认值或者是""
		if (isDefaultValue(lazyInit)) {
			//如果父<beans/>根标签不为null，那么设置为父<beans/>根标签的同名属性值，否则默认设置为false
			lazyInit = (parentDefaults != null ? parentDefaults.getLazyInit() : FALSE_VALUE);
		}
		defaults.setLazyInit(lazyInit);

		//获取当前<beans/>根标签的default-merge属性值merge
		String merge = root.getAttribute(DEFAULT_MERGE_ATTRIBUTE);
		//如果是默认值或者是""
		if (isDefaultValue(merge)) {
			//如果父<beans/>根标签不为null，那么设置为父<beans/>根标签的同名属性值，否则默认设置为false
			merge = (parentDefaults != null ? parentDefaults.getMerge() : FALSE_VALUE);
		}
		defaults.setMerge(merge);

		//获取当前<beans/>根标签的default-autowire属性值autowire
		String autowire = root.getAttribute(DEFAULT_AUTOWIRE_ATTRIBUTE);
		//如果是默认值或者是""
		if (isDefaultValue(autowire)) {
			//如果父<beans/>根标签不为null，那么设置为父<beans/>根标签的同名属性值，否则默认设置为no
			autowire = (parentDefaults != null ? parentDefaults.getAutowire() : AUTOWIRE_NO_VALUE);
		}
		defaults.setAutowire(autowire);

		//如果设置了default-autowire-candidates属性值(这个属性没有默认值)
		if (root.hasAttribute(DEFAULT_AUTOWIRE_CANDIDATES_ATTRIBUTE)) {
			defaults.setAutowireCandidates(root.getAttribute(DEFAULT_AUTOWIRE_CANDIDATES_ATTRIBUTE));
		}
		//否则，如果父<beans/>根标签不为null，那么设置为父<beans/>根标签的同名属性值，否则默认设置为no
		else if (parentDefaults != null) {
			defaults.setAutowireCandidates(parentDefaults.getAutowireCandidates());
		}

		//如果设置了default-init-method属性值(这个属性没有默认值)
		if (root.hasAttribute(DEFAULT_INIT_METHOD_ATTRIBUTE)) {
			defaults.setInitMethod(root.getAttribute(DEFAULT_INIT_METHOD_ATTRIBUTE));
		}
		//否则，如果父<beans/>根标签不为null，那么设置为父<beans/>根标签的同名属性值，否则默认设置为no
		else if (parentDefaults != null) {
			defaults.setInitMethod(parentDefaults.getInitMethod());
		}

		//如果设置了default-destroy-method属性值(这个属性没有默认值)
		if (root.hasAttribute(DEFAULT_DESTROY_METHOD_ATTRIBUTE)) {
			defaults.setDestroyMethod(root.getAttribute(DEFAULT_DESTROY_METHOD_ATTRIBUTE));
		}
		//否则，如果父<beans/>根标签不为null，那么设置为父<beans/>根标签的同名属性值，否则默认设置为no
		else if (parentDefaults != null) {
			defaults.setDestroyMethod(parentDefaults.getDestroyMethod());
		}
		//设置配置源source属性，即当前root元素节点对象
		defaults.setSource(this.readerContext.extractSource(root));
	}

	/**
	 * Return the defaults definition object.
	 */
	public DocumentDefaultsDefinition getDefaults() {
		return this.defaults;
	}

	/**
	 * Return the default settings for bean definitions as indicated within
	 * the attributes of the top-level {@code <beans/>} element.
	 */
	public BeanDefinitionDefaults getBeanDefinitionDefaults() {
		BeanDefinitionDefaults bdd = new BeanDefinitionDefaults();
		bdd.setLazyInit(TRUE_VALUE.equalsIgnoreCase(this.defaults.getLazyInit()));
		bdd.setAutowireMode(getAutowireMode(DEFAULT_VALUE));
		bdd.setInitMethodName(this.defaults.getInitMethod());
		bdd.setDestroyMethodName(this.defaults.getDestroyMethod());
		return bdd;
	}

	/**
	 * Return any patterns provided in the 'default-autowire-candidates'
	 * attribute of the top-level {@code <beans/>} element.
	 */
	@Nullable
	public String[] getAutowireCandidatePatterns() {
		String candidatePattern = this.defaults.getAutowireCandidates();
		return (candidatePattern != null ? StringUtils.commaDelimitedListToStringArray(candidatePattern) : null);
	}


	/**
	 * Parses the supplied {@code <bean>} element. May return {@code null}
	 * if there were errors during parse. Errors are reported to the
	 * {@link org.springframework.beans.factory.parsing.ProblemReporter}.
	 */
	@Nullable
	public BeanDefinitionHolder parseBeanDefinitionElement(Element ele) {
		return parseBeanDefinitionElement(ele, null);
	}

	/**
	 * Parses the supplied {@code <bean>} element. May return {@code null}
	 * if there were errors during parse. Errors are reported to the
	 * {@link org.springframework.beans.factory.parsing.ProblemReporter}.
	 * 解析提供的<bean/>标签element元素。如果解析错误，则可能会返回null
	 * 直接使用element对象的相关方法即可获取对应<bean/>标签的属性值
	 */
	@Nullable
	public BeanDefinitionHolder parseBeanDefinitionElement(Element ele, @Nullable BeanDefinition containingBean) {
		// 解析id 属性
		String id = ele.getAttribute(ID_ATTRIBUTE);
		// 解析 name 属性
		String nameAttr = ele.getAttribute(NAME_ATTRIBUTE);
		// 将 name 属性的定义按照 “逗号、分号、空格” 切分，形成一个 别名列表数组，
		List<String> aliases = new ArrayList<>();
		if (StringUtils.hasLength(nameAttr)) {
			String[] nameArr = StringUtils.tokenizeToStringArray(nameAttr, MULTI_VALUE_ATTRIBUTE_DELIMITERS);
			aliases.addAll(Arrays.asList(nameArr));
		}

		String beanName = id;
		// 如果没有指定id, 那么用别名列表的第一个名字作为beanName
		if (!StringUtils.hasText(beanName) && !aliases.isEmpty()) {
			beanName = aliases.remove(0);
			if (logger.isTraceEnabled()) {
				logger.trace("No XML 'id' specified - using '" + beanName +
						"' as bean name and " + aliases + " as aliases");
			}
		}

		if (containingBean == null) {
			checkNameUniqueness(beanName, aliases, ele);
		}

		// 根据 <bean ...>...</bean> 中的配置创建 BeanDefinition，然后把配置中的信息都设置到实例中,
		AbstractBeanDefinition beanDefinition = parseBeanDefinitionElement(ele, beanName, containingBean);
		if (beanDefinition != null) {
			// 如果都没有设置 id 和 name，那么此时的 beanName 就会为 null，进入下面这块代码产生
			if (!StringUtils.hasText(beanName)) {
				try {
					if (containingBean != null) {
						beanName = BeanDefinitionReaderUtils.generateBeanName(
								beanDefinition, this.readerContext.getRegistry(), true);
					} else {
						// 如果我们不定义 id 和 name，系统生成 beanName
						beanName = this.readerContext.generateBeanName(beanDefinition);
						// Register an alias for the plain bean class name, if still possible,
						// if the generator returned the class name plus a suffix.
						// This is expected for Spring 1.2/2.0 backwards compatibility.
						String beanClassName = beanDefinition.getBeanClassName();
						if (beanClassName != null &&
								beanName.startsWith(beanClassName) && beanName.length() > beanClassName.length() &&
								!this.readerContext.getRegistry().isBeanNameInUse(beanClassName)) {
							// 把 beanClassName 设置为 Bean 的别名
							aliases.add(beanClassName);
						}
					}
					if (logger.isTraceEnabled()) {
						logger.trace("Neither XML 'id' nor 'name' specified - " +
								"using generated bean name [" + beanName + "]");
					}
				} catch (Exception ex) {
					error(ex.getMessage(), ele);
					return null;
				}
			}
			String[] aliasesArray = StringUtils.toStringArray(aliases);
			// 返回 BeanDefinitionHolder
			return new BeanDefinitionHolder(beanDefinition, beanName, aliasesArray);
		}

		return null;
	}

	/**
	 * Validate that the specified bean name and aliases have not been used already
	 * within the current level of beans element nesting.
	 * 检查当前<bean/>标签的名字和别名是否被所属的<beans/>标签下的其他bean使用了
	 * 如果被使用了那么直接抛出异常
	 */
	protected void checkNameUniqueness(String beanName, List<String> aliases, Element beanElement) {
		//被找到的name
		String foundName = null;
		//如果beanName不为空并且usedNames已经包含了这个beanName
		if (StringUtils.hasText(beanName) && this.usedNames.contains(beanName)) {
			//foundName赋值给beanName
			foundName = beanName;
		}
		//如果没有找到beanName，那么继续找aliases
		if (foundName == null) {
			//获取第一个找到的name赋值给beanName
			foundName = CollectionUtils.findFirstMatch(this.usedNames, aliases);
		}
		//如果foundName(被找到的name)属性不为null，说明有重复的名字和别名，直接抛出异常
		if (foundName != null) {
			error("Bean name '" + foundName + "' is already used in this <beans/> element", beanElement);
		}
		//到这里，表示没找到重复的，将正在解析的bean的名字和别名添加进去
		this.usedNames.add(beanName);
		this.usedNames.addAll(aliases);
	}

	/**
	 * Parse the bean definition itself, without regard to name or aliases. May return
	 * {@code null} if problems occurred during the parsing of the bean definition.
	 * 解析bean定义本身，而不考虑名称或别名。如果在解析bean定义期间出现问题，可能会返回 null。
	 */
	@Nullable
	public AbstractBeanDefinition parseBeanDefinitionElement(
			Element ele, String beanName, @Nullable BeanDefinition containingBean) {

		this.parseState.push(new BeanEntry(beanName));

		// 解析class 属性
		String className = null;
		if (ele.hasAttribute(CLASS_ATTRIBUTE)) {
			className = ele.getAttribute(CLASS_ATTRIBUTE).trim();
		}
		// 解析parent 属性
		String parent = null;
		if (ele.hasAttribute(PARENT_ATTRIBUTE)) {
			parent = ele.getAttribute(PARENT_ATTRIBUTE);
		}

		try {
			// 创建 BeanDefinition对象，实际实现类为 GenericBeanDefinition
			AbstractBeanDefinition bd = createBeanDefinition(className, parent);
			// 设置 BeanDefinition 其他属性
			parseBeanDefinitionAttributes(ele, beanName, containingBean, bd);
			// 设置 Description 属性信息
			bd.setDescription(DomUtils.getChildElementValueByTagName(ele, DESCRIPTION_ELEMENT));
			//下面解析 <bean>......</bean> 内部的子元素，解析出来以后的信息都放到 bd 的属性中

			// 解析 <meta />
			parseMetaElements(ele, bd);
			// 解析 <lookup-method />
			parseLookupOverrideSubElements(ele, bd.getMethodOverrides());
			// 解析 <replaced-method />
			parseReplacedMethodSubElements(ele, bd.getMethodOverrides());

			// 解析 <constructor-arg />
			parseConstructorArgElements(ele, bd);
			// 解析 <property />
			parsePropertyElements(ele, bd);
			// 解析 <qualifier />
			parseQualifierElements(ele, bd);

			bd.setResource(this.readerContext.getResource());
			bd.setSource(extractSource(ele));

			return bd;
		} catch (ClassNotFoundException ex) {
			error("Bean class [" + className + "] not found", ele, ex);
		} catch (NoClassDefFoundError err) {
			error("Class that bean class [" + className + "] depends on not found", ele, err);
		} catch (Throwable ex) {
			error("Unexpected failure during bean definition parsing", ele, ex);
		} finally {
			this.parseState.pop();
		}

		return null;
	}

	/**
	 * Apply the attributes of the given bean element to the given bean * definition.
	 * <p>
	 * 解析<bean/>标签元素的各种属性，并且设置给当前的bean定义（BeanDefinition）
	 * <p>
	 * 在此前createDelegate方法创建解析器的时候，就已经解析了外部<beans/>根标签的属性，并存入defaults属性对象中
	 * 在parseBeanDefinitionAttributes方法中某些属性如果没有设置值，那么将使用外部<beans/>标签的属性值
	 * 如果当前bean自己设置了值，那么就不会使用外部<beans/>根标签的属性，这就是我们在IoC学习过程中所说的一些属性设置覆盖原则
	 *
	 * @param ele            bean declaration element  <bean/>标签元素
	 * @param beanName       bean name  bean名字
	 * @param containingBean containing bean definition 当前的bean的包含bean定义
	 * @return a bean definition initialized according to the bean element attributes
	 */
	public AbstractBeanDefinition parseBeanDefinitionAttributes(Element ele, String beanName,
																@Nullable BeanDefinition containingBean, AbstractBeanDefinition bd) {
		/*如果具有singleton属性*/
		if (ele.hasAttribute(SINGLETON_ATTRIBUTE)) {
			//抛出异常，singleton属性在1.x版本之后已经不被支持，使用scope属性代替
			//error("Old 1.x 'singleton' attribute in use - upgrade to 'scope' declaration", ele);
		}
		/*否则，如果具有scope属性*/
		else if (ele.hasAttribute(SCOPE_ATTRIBUTE)) {
			//设置scope属性值
			bd.setScope(ele.getAttribute(SCOPE_ATTRIBUTE));
		}
		/*否则，当前bean是一个内部bean，进行内部bean处理*/
		else if (containingBean != null) {
			//内部bean的定义接收外部包含bean定义的属性
			//这里能够看出来，内部bean自己设置scope属性值是没意义的
			bd.setScope(containingBean.getScope());
		}
		//如果具有abstract属性
		if (ele.hasAttribute(ABSTRACT_ATTRIBUTE)) {
			//设置abstract属性
			bd.setAbstract(TRUE_VALUE.equals(ele.getAttribute(ABSTRACT_ATTRIBUTE)));
		}
		//获取lazy-init属性的值lazyInit
		String lazyInit = ele.getAttribute(LAZY_INIT_ATTRIBUTE);
		//如果是默认值或者是""
		if (isDefaultValue(lazyInit)) {
			//那么使用外部<beans/>标签的属性值
			lazyInit = this.defaults.getLazyInit();
		}
		//设置lazyInit属性
		bd.setLazyInit(TRUE_VALUE.equals(lazyInit));

		//获取autowire属性的值autowire
		String autowire = ele.getAttribute(AUTOWIRE_ATTRIBUTE);
		//设置autowire属性模式
		bd.setAutowireMode(getAutowireMode(autowire));

		//如果设置了depends-on属性值(depends-on属性没有默认值)
		if (ele.hasAttribute(DEPENDS_ON_ATTRIBUTE)) {
			//获取depends-on属性的值dependsOn
			String dependsOn = ele.getAttribute(DEPENDS_ON_ATTRIBUTE);
			//设置dependsOn属性，使用","、";"、" "拆分成为一个数组
			bd.setDependsOn(StringUtils.tokenizeToStringArray(dependsOn, MULTI_VALUE_ATTRIBUTE_DELIMITERS));
		}
		//获取autowire-candidate属性值autowireCandidate
		String autowireCandidate = ele.getAttribute(AUTOWIRE_CANDIDATE_ATTRIBUTE);
		//如果是默认值或者是""
		if (isDefaultValue(autowireCandidate)) {
			//那么获取外部<beans/>标签的default-autowire-candidate属性值
			String candidatePattern = this.defaults.getAutowireCandidates();
			if (candidatePattern != null) {
				//使用","拆分为数组
				String[] patterns = StringUtils.commaDelimitedListToStringArray(candidatePattern);
				//是否匹配当前bean的名字，如果匹配，那么设置为true，否则设置为false
				bd.setAutowireCandidate(PatternMatchUtils.simpleMatch(patterns, beanName));
			}
		}
		//如果设置了值，如果是字符串值是"true"，那么属性设置为true,否则设置为false
		else {
			bd.setAutowireCandidate(TRUE_VALUE.equals(autowireCandidate));
		}

		//如果设置了primary属性值(primary属性没有默认值)
		if (ele.hasAttribute(PRIMARY_ATTRIBUTE)) {
			//如果是字符串值是"true"，那么属性设置为true,否则设置为false
			bd.setPrimary(TRUE_VALUE.equals(ele.getAttribute(PRIMARY_ATTRIBUTE)));
		}

		//如果设置了init-method属性值(init-method属性没有默认值)
		if (ele.hasAttribute(INIT_METHOD_ATTRIBUTE)) {
			//设置该值
			String initMethodName = ele.getAttribute(INIT_METHOD_ATTRIBUTE);
			bd.setInitMethodName(initMethodName);
		}

		//否则，如果外部<beans/>标签的default-init-method属性不为null
		else if (this.defaults.getInitMethod() != null) {
			//那么设置为外部<beans/>标签的default-init-method属性值
			bd.setInitMethodName(this.defaults.getInitMethod());
			bd.setEnforceInitMethod(false);
		}

		//如果设置了destroy-method属性值(destroy-method属性没有默认值)
		if (ele.hasAttribute(DESTROY_METHOD_ATTRIBUTE)) {
			//设置该值
			String destroyMethodName = ele.getAttribute(DESTROY_METHOD_ATTRIBUTE);
			bd.setDestroyMethodName(destroyMethodName);
		}
		//否则，如果外部<beans/>标签的default-destroy-method属性不为null
		else if (this.defaults.getDestroyMethod() != null) {
			//那么设置为外部<beans/>标签的default-destroy-method属性值
			bd.setDestroyMethodName(this.defaults.getDestroyMethod());
			bd.setEnforceDestroyMethod(false);
		}

		//如果设置了factory-method属性值(factory-method属性没有默认值)
		if (ele.hasAttribute(FACTORY_METHOD_ATTRIBUTE)) {
			//设置该值
			bd.setFactoryMethodName(ele.getAttribute(FACTORY_METHOD_ATTRIBUTE));
		}
		//如果设置了factory-bean属性值(factory-bean属性没有默认值)
		if (ele.hasAttribute(FACTORY_BEAN_ATTRIBUTE)) {
			//设置该值
			bd.setFactoryBeanName(ele.getAttribute(FACTORY_BEAN_ATTRIBUTE));
		}
		//将<bean/>标签对应的element元素中的属性获取出来经过处理之后设置到了当前bean的BeanDefinition之后即可返回
		return bd;
	}

	/**
	 * Create a bean definition for the given class name and parent name.
	 * 使用给定的bean className和bean parentName属性创建BeanDefinition对象。
	 * BeanDefinition的实际类型是GenericBeanDefinition类型
	 *
	 * @param className  the name of the bean class bean的class属性值
	 * @param parentName the name of the bean's parent bean bean的parent属性值
	 * @return the newly created bean definition 新创建的 bean 定义(BeanDefinition)
	 * @throws ClassNotFoundException if bean class resolution was attempted but failed 解析bean的class失败
	 */
	protected AbstractBeanDefinition createBeanDefinition(@Nullable String className, @Nullable String parentName)
			throws ClassNotFoundException {
		//调用BeanDefinitionReaderUtils工具类的方法
		return BeanDefinitionReaderUtils.createBeanDefinition(
				parentName, className, this.readerContext.getBeanClassLoader());
	}

	/**
	 * Parse the meta elements underneath the given element, if any.
	 * 解析给定元素下的元元素（如果有）。
	 */
	public void parseMetaElements(Element ele, BeanMetadataAttributeAccessor attributeAccessor) {
		// 获取当前节点的所有子节点
		NodeList nl = ele.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			// 提取meta
			if (isCandidateElement(node) && nodeNameEquals(node, META_ELEMENT)) {
				Element metaElement = (Element) node;
				String key = metaElement.getAttribute(KEY_ATTRIBUTE);
				String value = metaElement.getAttribute(VALUE_ATTRIBUTE);
				// 使用key value 构建 BeanMetadataAttribute 对象
				BeanMetadataAttribute attribute = new BeanMetadataAttribute(key, value);
				attribute.setSource(extractSource(metaElement));
				attributeAccessor.addMetadataAttribute(attribute);
			}
		}
	}

	/**
	 * Parse the given autowire attribute value into
	 * {@link AbstractBeanDefinition} autowire constants.
	 * 将给定的autowire属性的String类型的属性值转换为int类型的属性mode
	 * 默认不0自动注入，除非XML设置
	 */
	@SuppressWarnings("deprecation")
	public int getAutowireMode(String attrValue) {
		String attr = attrValue;
		//如果是默认值或者是""
		if (isDefaultValue(attr)) {
			//那么使用外部<beans/>标签的属性值
			attr = this.defaults.getAutowire();
		}
		//默认0，不自动注入
		int autowire = AbstractBeanDefinition.AUTOWIRE_NO;
		//其他模式， byName
		if (AUTOWIRE_BY_NAME_VALUE.equals(attr)) {
			autowire = AbstractBeanDefinition.AUTOWIRE_BY_NAME;
		}
		//byType
		else if (AUTOWIRE_BY_TYPE_VALUE.equals(attr)) {
			autowire = AbstractBeanDefinition.AUTOWIRE_BY_TYPE;
		}
		//constructor
		else if (AUTOWIRE_CONSTRUCTOR_VALUE.equals(attr)) {
			autowire = AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR;
		}
		//autodetect 被废弃了
		else if (AUTOWIRE_AUTODETECT_VALUE.equals(attr)) {
			autowire = AbstractBeanDefinition.AUTOWIRE_AUTODETECT;
		}
		// 否则返回值默认值
		return autowire;
	}

	/**
	 * Parse constructor-arg sub-elements of the given bean element.
	 * 解析给定bean元素的构造函数arg子元素。
	 */
	public void parseConstructorArgElements(Element beanEle, BeanDefinition bd) {
		//获取<bean/>标签元素下的所有子节点元素
		NodeList nl = beanEle.getChildNodes();
		//遍历
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			//如果当前节点是一个Element标签节点并且标签名为"constructor-arg"
			if (isCandidateElement(node) && nodeNameEquals(node, CONSTRUCTOR_ARG_ELEMENT)) {
				//那么解析这个<constructor-arg/>子标签
				parseConstructorArgElement((Element) node, bd);
			}
		}
	}

	/**
	 * Parse property sub-elements of the given bean element.
	 * 解析bean标签下property子标签
	 */
	public void parsePropertyElements(Element beanEle, BeanDefinition bd) {
		//获取<bean/>标签元素下的所有子节点标签元素
		NodeList nl = beanEle.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			//如果当前节点是一个Element标签节点并且标签名为"property"
			if (isCandidateElement(node) && nodeNameEquals(node, PROPERTY_ELEMENT)) {
				//标签名为property才能进入，进入这个方法
				parsePropertyElement((Element) node, bd);
			}
		}
	}

	/**
	 * Parse qualifier sub-elements of the given bean element.
	 */
	public void parseQualifierElements(Element beanEle, AbstractBeanDefinition bd) {
		NodeList nl = beanEle.getChildNodes();
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			if (isCandidateElement(node) && nodeNameEquals(node, QUALIFIER_ELEMENT)) {
				parseQualifierElement((Element) node, bd);
			}
		}
	}

	/**
	 * Parse lookup-override sub-elements of the given bean element.
	 */
	public void parseLookupOverrideSubElements(Element beanEle, MethodOverrides overrides) {
		NodeList nl = beanEle.getChildNodes();
		// 遍历子节点
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			if (isCandidateElement(node) && nodeNameEquals(node, LOOKUP_METHOD_ELEMENT)) {
				Element ele = (Element) node;
				String methodName = ele.getAttribute(NAME_ATTRIBUTE);
				String beanRef = ele.getAttribute(BEAN_ELEMENT);
				// 创建 LookupOverride 对象
				LookupOverride override = new LookupOverride(methodName, beanRef);
				override.setSource(extractSource(ele));
				// 添加到 MethodOverrides 中
				overrides.addOverride(override);
			}
		}
	}

	/**
	 * Parse replaced-method sub-elements of the given bean element.
	 */
	public void parseReplacedMethodSubElements(Element beanEle, MethodOverrides overrides) {
		NodeList nl = beanEle.getChildNodes();
		// 遍历子节点
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			if (isCandidateElement(node) && nodeNameEquals(node, REPLACED_METHOD_ELEMENT)) {
				Element replacedMethodEle = (Element) node;
				String name = replacedMethodEle.getAttribute(NAME_ATTRIBUTE);
				String callback = replacedMethodEle.getAttribute(REPLACER_ATTRIBUTE);
				ReplaceOverride replaceOverride = new ReplaceOverride(name, callback);
				// Look for arg-type match elements.
				List<Element> argTypeEles = DomUtils.getChildElementsByTagName(replacedMethodEle, ARG_TYPE_ELEMENT);
				for (Element argTypeEle : argTypeEles) {
					String match = argTypeEle.getAttribute(ARG_TYPE_MATCH_ATTRIBUTE);
					match = (StringUtils.hasText(match) ? match : DomUtils.getTextValue(argTypeEle));
					if (StringUtils.hasText(match)) {
						replaceOverride.addTypeIdentifier(match);
					}
				}
				replaceOverride.setSource(extractSource(replacedMethodEle));
				// 添加到 MethodOverrides 中
				overrides.addOverride(replaceOverride);
			}
		}
	}

	/**
	 * Parse a constructor-arg element.
	 * 解析一个<constructor-arg/>标签元素,将解析后的结果封装到BeanDefinition中的ConstructorArgumentValues属性中
	 */
	public void parseConstructorArgElement(Element ele, BeanDefinition bd) {
		// 获取index 属性
		String indexAttr = ele.getAttribute(INDEX_ATTRIBUTE);
		// 获取type 属性
		String typeAttr = ele.getAttribute(TYPE_ATTRIBUTE);
		// 获取 name 属性
		String nameAttr = ele.getAttribute(NAME_ATTRIBUTE);
		/*如果indexAttr不为null也不为""，即设置了<constructor-arg/>标签的index属性值*/
		if (StringUtils.hasLength(indexAttr)) {
			try {
				//解析为Integer类型
				int index = Integer.parseInt(indexAttr);
				//小于0就抛出异常
				if (index < 0) {
					error("'index' cannot be lower than 0", ele);
				}
				//大于等于0
				else {
					try {
						//记录解析状态
						this.parseState.push(new ConstructorArgumentEntry(index));

						/*核心代码。解析<constructor-arg/>子标签元素的值，可能是value属性、ref属性、或者子标签，返回相应的值封装对象*/
						Object value = parsePropertyValue(ele, bd, null);

						//解析的value使用ConstructorArgumentValues.ValueHolder类型的valueHolder对象包装
						ConstructorArgumentValues.ValueHolder valueHolder = new ConstructorArgumentValues.ValueHolder(value);
						//如果type属性不为null也不是""
						if (StringUtils.hasLength(typeAttr)) {
							//封装type属性到valueHolder
							valueHolder.setType(typeAttr);
						}
						//如果name属性不为null也不是""
						if (StringUtils.hasLength(nameAttr)) {
							//封装name属性到valueHolder
							valueHolder.setName(nameAttr);
						}
						//设置源
						valueHolder.setSource(extractSource(ele));
						/*
						 * 从BeanDefinition中查找，判断当前index是否已存在
						 * 如果存在，说明index属性重复，抛出异常
						 */
						if (bd.getConstructorArgumentValues().hasIndexedArgumentValue(index)) {
							error("Ambiguous constructor-arg entries for index " + index, ele);
						}
						/*
						 * 如果不存在，那么正常
						 */
						else {
							//将当前constructor-arg子标签的解析结果以key-value的形式封装，key就是index，value就是valueHolder
							//封装到BeanDefinition中的ConstructorArgumentValues属性中的indexedArgumentValues属性中
							bd.getConstructorArgumentValues().addIndexedArgumentValue(index, valueHolder);
						}
					} finally {
						this.parseState.pop();
					}
				}
			} catch (NumberFormatException ex) {
				//无法转换为数值，抛出异常
				error("Attribute 'index' of tag 'constructor-arg' must be an integer", ele);
			}
		}
		/*如果indexAttr为null或者""，即没有设置<constructor-arg/>标签的index属性值*/
		else {
			try {
				this.parseState.push(new ConstructorArgumentEntry());

				/*核心代码。解析<constructor-arg/>子标签元素的值，可能来自于是value属性、ref属性、或者子标签，返回相应的值封装对象*/
				Object value = parsePropertyValue(ele, bd, null);

				//解析的value使用ConstructorArgumentValues.ValueHolder类型的valueHolder对象包装
				ConstructorArgumentValues.ValueHolder valueHolder = new ConstructorArgumentValues.ValueHolder(value);
				//如果type属性不为null也不是""
				if (StringUtils.hasLength(typeAttr)) {
					//封装type属性到valueHolder
					valueHolder.setType(typeAttr);
				}
				//如果name属性不为null也不是""
				if (StringUtils.hasLength(nameAttr)) {
					//封装name属性到valueHolder
					valueHolder.setName(nameAttr);
				}
				//设置源
				valueHolder.setSource(extractSource(ele));
				//将当前<constructor-arg/>子标签的解析结果封装到BeanDefinition中的
				//ConstructorArgumentValues属性中的genericArgumentValues属性中
				bd.getConstructorArgumentValues().addGenericArgumentValue(valueHolder);
			} finally {
				this.parseState.pop();
			}
		}
	}

	/**
	 * Parse a property element.
	 * 解析一个<property/>标签元素
	 */
	public void parsePropertyElement(Element ele, BeanDefinition bd) {

		//拿到property标签的name属性
		String propertyName = ele.getAttribute(NAME_ATTRIBUTE);
		if (!StringUtils.hasLength(propertyName)) {
			error("Tag 'property' must have a 'name' attribute", ele);
			return;
		}

		//解析的时候放入，解析完成弹出，这里放入property标签，
		//这里面还存有bean父标签，子标签解析完成后先弹出
		this.parseState.push(new PropertyEntry(propertyName));
		try {

			//bean标签下可以有多个property，但是不能重复name属性
			if (bd.getPropertyValues().contains(propertyName)) {
				error("Multiple 'property' definitions for property '" + propertyName + "'", ele);
				return;
			}

			/*
			 * 核心代码。解析<property/>子标签元素的值，可能是value属性、ref属性、或者子标签，返回相应的值封装对象
			 *
			 * 这个方法和解析constructor-arg标签的代码中调用的是同一个方法，区别是propertyName属性不为null
			 * propertyName属性仅仅是为了异常日志打印而已，没有其他作用
			 */
			Object val = parsePropertyValue(ele, bd, propertyName);

			//将name属性和对应的value放入
			PropertyValue pv = new PropertyValue(propertyName, val);

			//解析property标签的子标签meta，
			//拿到meta的key和value属性，设置到PropertyValue中
			parseMetaElements(ele, pv);

			//这里没有实现，为null
			pv.setSource(extractSource(ele));

			//将PropertyValue添加到bean definition中
			bd.getPropertyValues().addPropertyValue(pv);
		} finally {

			//解析的时候放入，解析完成弹出，这里放入property标签
			this.parseState.pop();
		}
	}

	/**
	 * Parse a qualifier element.
	 */
	public void parseQualifierElement(Element ele, AbstractBeanDefinition bd) {
		String typeName = ele.getAttribute(TYPE_ATTRIBUTE);
		if (!StringUtils.hasLength(typeName)) {
			error("Tag 'qualifier' must have a 'type' attribute", ele);
			return;
		}
		this.parseState.push(new QualifierEntry(typeName));
		try {
			AutowireCandidateQualifier qualifier = new AutowireCandidateQualifier(typeName);
			qualifier.setSource(extractSource(ele));
			String value = ele.getAttribute(VALUE_ATTRIBUTE);
			if (StringUtils.hasLength(value)) {
				qualifier.setAttribute(AutowireCandidateQualifier.VALUE_KEY, value);
			}
			NodeList nl = ele.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);
				if (isCandidateElement(node) && nodeNameEquals(node, QUALIFIER_ATTRIBUTE_ELEMENT)) {
					Element attributeEle = (Element) node;
					String attributeName = attributeEle.getAttribute(KEY_ATTRIBUTE);
					String attributeValue = attributeEle.getAttribute(VALUE_ATTRIBUTE);
					if (StringUtils.hasLength(attributeName) && StringUtils.hasLength(attributeValue)) {
						BeanMetadataAttribute attribute = new BeanMetadataAttribute(attributeName, attributeValue);
						attribute.setSource(extractSource(attributeEle));
						qualifier.addMetadataAttribute(attribute);
					} else {
						error("Qualifier 'attribute' tag must have a 'name' and 'value'", attributeEle);
						return;
					}
				}
			}
			bd.addQualifier(qualifier);
		} finally {
			this.parseState.pop();
		}
	}

	/**
	 * Get the value of a property element. May be a list etc.
	 * Also used for constructor arguments, "propertyName" being null in this case.
	 * <p>
	 * 用于解析、获取<property/>、<constructor-arg/>标签元素的值
	 * 这里的值可能来自value属性、ref属性，或者是一个<list/>、<map/>等等子标签
	 * <p>
	 * 在解析<constructor-arg/>标签的值时，"propertyName"属性为 null
	 * 在解析<property/>标签的值时，"propertyName"属性为不为null
	 * 这个propertyName属性主要用于异常日志输出，没啥其他用处。
	 *
	 * @param ele          对应标签元素
	 * @param bd           当前<bean/>标签元素对应的BeanDefinition
	 * @param propertyName 属性名
	 */
	@Nullable
	public Object parsePropertyValue(Element ele, BeanDefinition bd, @Nullable String propertyName) {
		String elementName = (propertyName != null ?
				"<property> element for property '" + propertyName + "'" :
				"<constructor-arg> element");

		/*
		 * 1 校验 值子标签只能有一个
		 *
		 * 对于<property/>、<constructor-arg/>标签内部不属于<description/>和<meta/>标签的其他子标签
		 * 比如<ref/>, <value/>, <list/>, <etc/>,<map/>等等，它们都被算作"值子标签"
		 * 值子标签就是对应着<property/>、<constructor-arg/>标签的值，Spring要求最多只能有其中一个值子标签
		 */
		//获取当前标签结点元素的子节点元素
		NodeList nl = ele.getChildNodes();
		//临时变量subElement记录找到的子节点标签
		Element subElement = null;
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			//如果node属于标签，并且不属于<description/>和<meta/>标签，比如<ref/>, <value/>, <list/>, <etc/>,<map/>等等
			if (node instanceof Element currentElement && !nodeNameEquals(node, DESCRIPTION_ELEMENT) &&
					!nodeNameEquals(node, META_ELEMENT)) {
				// Child element is what we're looking for.
				//如果subElement不为null，说明在循环中找到了多个值子标签，那么直接抛出异常
				if (subElement != null) {
					error(elementName + " must not contain more than one sub-element", ele);
				}
				//否则，subElement记录找到的子节点标签
				else {
					subElement = currentElement;
				}
			}
		}
		/*
		 * 2 到这里，表示校验通过，可能有一个值子标签，也可能一个都没有
		 *
		 * 下面继续校验：ref属性、value属性、值子标签这三者只能有一个
		 */
		//是否具有ref属性
		boolean hasRefAttribute = ele.hasAttribute(REF_ATTRIBUTE);
		//是否具有value属性
		boolean hasValueAttribute = ele.hasAttribute(VALUE_ATTRIBUTE);
		//如果同时具有ref属性、value属性、子标签这三者中的多个
		if ((hasRefAttribute && hasValueAttribute) ||
				((hasRefAttribute || hasValueAttribute) && subElement != null)) {
			//那么抛出异常，property标签和constructor-arg标签只能拥有ref属性、value属性、子标签这三者中的一个
			error(elementName +
					" is only allowed to contain either 'ref' attribute OR 'value' attribute OR sub-element", ele);
		}

		/*3 校验通过，开始解析*/

		/*3.1如果具有ref属性，那么肯定没有value属性以及值子标签*/
		if (hasRefAttribute) {
			//获取ref属性值refName
			String refName = ele.getAttribute(REF_ATTRIBUTE);
			//如果refName为空，那么抛出异常
			if (!StringUtils.hasText(refName)) {
				error(elementName + " contains empty 'ref' attribute", ele);
			}
			//创建一个运行时bean引用对象，持有refName，相当于占位符，在后面运行时将会被解析为refName对应的bean实例的引用
			RuntimeBeanReference ref = new RuntimeBeanReference(refName);
			//设置源，运行时将根据源解析
			ref.setSource(extractSource(ele));
			//返回这个对象
			return ref;
		}
		/*3.2 否则，如果具有value属性，那么肯定没有其他二者*/
		else if (hasValueAttribute) {
			//创建一个TypedStringValue对象，仅仅持有value字符串的，运行时值可以转换为其它类型，比如int、long等等
			//不过，转换的这个功能是由conversion转换服务提供的
			TypedStringValue valueHolder = new TypedStringValue(ele.getAttribute(VALUE_ATTRIBUTE));
			//设置源，运行时将根据源解析
			valueHolder.setSource(extractSource(ele));
			//返回这个对象
			return valueHolder;
		}
		/*3.3 否则，如果具有值子标签，那么肯定没有ref属性以及value属性*/
		else if (subElement != null) {
			/*继续解析值子标签*/
			return parsePropertySubElement(subElement, bd);
		}
		/*3.4 否则，啥都没有，抛出异常*/
		else {
			// Neither child element nor "ref" or "value" attribute found.
			error(elementName + " must specify a ref or value", ele);
			//返回null
			return null;
		}
	}

	/**
	 * Parse a value, ref or collection sub-element of a property or
	 * constructor-arg element.
	 * 该方法仅仅被parsePropertyValue(Element ele, BeanDefinition bd, @Nullable String propertyName)方法调用
	 * 解析<bean/>、<ref>、<idref>标签下面的一个<bean/>、<ref>、<idref>……等值子标签
	 *
	 * @param ele subelement of property element; we don't know which yet
	 *            <bean/>、<ref>、<idref>标签的一个值子标签元素，不知道具体是什么类型的
	 * @param bd  the current bean definition (if any)
	 *            当前所属<bean/>标签元素对应的BeanDefinition
	 */
	@Nullable
	public Object parsePropertySubElement(Element ele, @Nullable BeanDefinition bd) {
		//调用另一个parsePropertySubElement方法，第三个属性为null
		return parsePropertySubElement(ele, bd, null);
	}

	/**
	 * Parse a value, ref or collection sub-element of a property or
	 * constructor-arg element.
	 * <p>
	 * 该方法用于解析<property/>或者<constructor-arg/>标签下面的值子标签
	 * 同时用于解析单value集合标签(<array/>、<list/>、<set/>)下的值子标签
	 * 以及<map/>集合标签下的<key/>标签的子标签和表示value的值子标签
	 *
	 * @param ele              subelement of property element; we don't know which yet
	 *                         <property/>或者<constructor-arg/>标签的一个值子标签元素，不知道具体是什么类型的
	 * @param bd               the current bean definition (if any)
	 *                         当前所属<bean/>标签元素对应的BeanDefinition
	 * @param defaultValueType the default type (class name) for any
	 *                         {@code <value>} tag that might be created
	 *                         <value/>标签的默认value-type属性，这里的<value/>标签是指作为集合相关标签的子标签，
	 *                         它将会使用它的父集合标签的value-type或者key-type属性值作为自己的属性
	 *                         而不是property或者constructor-arg标签下面直接设置的<value/>子标签
	 */
	@Nullable
	public Object parsePropertySubElement(Element ele, @Nullable BeanDefinition bd, @Nullable String defaultValueType) {
		/*
		 * 1 如果不是默认命名空间下的标签，那么走自定义的解析逻辑
		 */
		if (!isDefaultNamespace(ele)) {
			//会返回一个BeanDefinitionHolder作为值
			return parseNestedCustomElement(ele, bd);
		}
		/*
		 * 2 否则，如果值子标签是<bean/>标签，这表示内部bean
		 */
		else if (nodeNameEquals(ele, BEAN_ELEMENT)) {
			//那么递归调用parseBeanDefinitionElement解析内部bean，这里的db就不为null，使用的就是外部包含bean的BeanDefinition
			BeanDefinitionHolder nestedBd = parseBeanDefinitionElement(ele, bd);
			if (nestedBd != null) {
				//对于<bean/>子标签下的自定义属性和子标签进行解析
				nestedBd = decorateBeanDefinitionIfRequired(ele, nestedBd, bd);
			}
			//返回BeanDefinitionHolder对象作为解析后的值
			return nestedBd;
		}
		/*
		 * 3 否则，如果子标签是<ref/>标签，这表示引用其他bean
		 */
		else if (nodeNameEquals(ele, REF_ELEMENT)) {
			// 获取bean属性的值，即引用的其他bean的名字refName
			String refName = ele.getAttribute(BEAN_REF_ATTRIBUTE);
			boolean toParent = false;
			//如果当前标签的refName为null或者""
			if (!StringUtils.hasLength(refName)) {
				//那么获取parent属性的值，将其作为引用的其他bean的名字refName
				refName = ele.getAttribute(PARENT_REF_ATTRIBUTE);
				toParent = true;
				//如果还是空的，那么抛出异常
				if (!StringUtils.hasLength(refName)) {
					error("'bean' or 'parent' is required for <ref> element", ele);
					return null;
				}
			}
			//如果还是空的或者不是文本，那么抛出异常
			if (!StringUtils.hasText(refName)) {
				error("<ref> element contains empty target attribute", ele);
				return null;
			}
			//同样使用RuntimeBeanReference包装该属性值作为解析后的值，将会在运行时转换为对其他bean的引用
			RuntimeBeanReference ref = new RuntimeBeanReference(refName, toParent);
			ref.setSource(extractSource(ele));
			return ref;
		}
		/*
		 * 4 否则，如果子标签是<idref/>标签，这表示引用字符串并校验bean
		 */
		else if (nodeNameEquals(ele, IDREF_ELEMENT)) {
			return parseIdRefElement(ele);
		}
		/*
		 * 5 否则，如果子标签是<value/>标签，这表示引用字面量值
		 */
		else if (nodeNameEquals(ele, VALUE_ELEMENT)) {
			return parseValueElement(ele, defaultValueType);
		}
		/*
		 * 6 否则，如果子标签是<null/>标签，这表示使用null作为值
		 */
		else if (nodeNameEquals(ele, NULL_ELEMENT)) {
			//包装成为TypedStringValue作为解析后的值返回
			TypedStringValue nullHolder = new TypedStringValue(null);
			nullHolder.setSource(extractSource(ele));
			return nullHolder;
		}
		/*
		 * 7 否则，如果子标签是<array/>标签，这表示值是一个数组
		 */
		else if (nodeNameEquals(ele, ARRAY_ELEMENT)) {
			return parseArrayElement(ele, bd);
		}
		/*
		 * 8 否则，如果子标签是<list/>标签，这表示值是一个list集合
		 */
		else if (nodeNameEquals(ele, LIST_ELEMENT)) {
			return parseListElement(ele, bd);
		}
		/*
		 * 9 否则，如果子标签是<set/>标签，这表示值是一个set集合
		 */
		else if (nodeNameEquals(ele, SET_ELEMENT)) {
			return parseSetElement(ele, bd);
		}
		/*
		 * 10 否则，如果子标签是<map/>标签，这表示值是一个map集合
		 */
		else if (nodeNameEquals(ele, MAP_ELEMENT)) {
			return parseMapElement(ele, bd);
		}
		/*
		 * 11 否则，如果子标签是<props/>标签，这表示值是一个properties集合
		 */
		else if (nodeNameEquals(ele, PROPS_ELEMENT)) {
			return parsePropsElement(ele);
		}
		/*
		 * 12 否则，没找到任何值子标签，抛出异常
		 */
		else {
			error("Unknown property sub-element: [" + ele.getNodeName() + "]", ele);
			return null;
		}
	}

	/**
	 * Return a typed String value Object for the given 'idref' element.
	 */
	@Nullable
	public Object parseIdRefElement(Element ele) {
		// A generic reference to any name of any bean.
		String refName = ele.getAttribute(BEAN_REF_ATTRIBUTE);
		if (!StringUtils.hasLength(refName)) {
			error("'bean' is required for <idref> element", ele);
			return null;
		}
		if (!StringUtils.hasText(refName)) {
			error("<idref> element contains empty target attribute", ele);
			return null;
		}
		RuntimeBeanNameReference ref = new RuntimeBeanNameReference(refName);
		ref.setSource(extractSource(ele));
		return ref;
	}

	/**
	 * Return a typed String value Object for the given value element.
	 * 返回给定<value/>值标签元素的解析的TypedStringValue对象
	 */
	public Object parseValueElement(Element ele, @Nullable String defaultTypeName) {
		//获取字面值
		String value = DomUtils.getTextValue(ele);
		//获取type属性值specifiedTypeName
		String specifiedTypeName = ele.getAttribute(TYPE_ATTRIBUTE);
		String typeName = specifiedTypeName;
		//如果没有指定类型，那么使用外部标签指定的type
		if (!StringUtils.hasText(typeName)) {
			typeName = defaultTypeName;
		}
		try {
			//使用typeName和value创建TypedStringValue对象typedValue返回
			TypedStringValue typedValue = buildTypedStringValue(value, typeName);
			typedValue.setSource(extractSource(ele));
			typedValue.setSpecifiedTypeName(specifiedTypeName);
			return typedValue;
		} catch (ClassNotFoundException ex) {
			error("Type class [" + typeName + "] not found for <value> element", ele, ex);
			return value;
		}
	}

	/**
	 * Build a typed String value Object for the given raw value.
	 *
	 * @see org.springframework.beans.factory.config.TypedStringValue
	 * 使用给定的值和目标类型，构建一个TypedStringValue,TypedStringValue用于包装<value/>值标签元素的解析结果，内部还可以指定需要转换的类型
	 */
	protected TypedStringValue buildTypedStringValue(String value, @Nullable String targetTypeName)
			throws ClassNotFoundException {

		ClassLoader classLoader = this.readerContext.getBeanClassLoader();
		TypedStringValue typedValue;
		//如果没有指定类型targetTypeName，那么使用默认那么使用要给value的参数构建一个TypedStringValue
		if (!StringUtils.hasText(targetTypeName)) {
			typedValue = new TypedStringValue(value);
		}
		//否则，如果类加载器不为null
		else if (classLoader != null) {
			//那么获取targetTypeName类型的Class对象，并构建一个TypedStringValue
			Class<?> targetType = ClassUtils.forName(targetTypeName, classLoader);
			typedValue = new TypedStringValue(value, targetType);
		}
		//否则，使用两个字符串参数构建一个
		else {
			typedValue = new TypedStringValue(value, targetTypeName);
		}
		return typedValue;
	}

	/**
	 * 解析<array/>标签元素
	 */
	public Object parseArrayElement(Element arrayEle, @Nullable BeanDefinition bd) {
		//获取value-type属性值elementType
		String elementType = arrayEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
		//获取<array/>标签的子标签集合，里面的一个子标签相当于一个数组元素
		NodeList nl = arrayEle.getChildNodes();
		//创建用于托管数组元素的标记集合类ManagedArray，其中可能包括运行时 bean 引用（要解析为 bean 对象）。
		//将会返回该对象作为解析结果
		ManagedArray target = new ManagedArray(elementType, nl.getLength());
		target.setSource(extractSource(arrayEle));
		//设置elementTypeName属性
		target.setElementTypeName(elementType);
		//设置mergeEnabled属性，表示是否需要合并父bean的集合属性
		target.setMergeEnabled(parseMergeAttribute(arrayEle));
		//调用parseCollectionElements继续解析
		parseCollectionElements(nl, target, bd, elementType);
		return target;
	}

	/**
	 * 解析<list/>标签元素
	 */
	public List<Object> parseListElement(Element collectionEle, @Nullable BeanDefinition bd) {
		//获取value-type属性值elementType
		String defaultElementType = collectionEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
		//获取<list/>标签的子标签集合，里面的一个子标签相当于一个list集合元素
		NodeList nl = collectionEle.getChildNodes();
		//创建用于托管List元素的标记集合类ManagedList，其中可能包括运行时 bean 引用（要解析为 bean 对象）。
		//将会返回该对象作为解析结果
		ManagedList<Object> target = new ManagedList<>(nl.getLength());
		target.setSource(extractSource(collectionEle));
		target.setElementTypeName(defaultElementType);
		//设置mergeEnabled属性，表示是否需要合并父bean的集合属性
		target.setMergeEnabled(parseMergeAttribute(collectionEle));
		//调用parseCollectionElements继续解析
		parseCollectionElements(nl, target, bd, defaultElementType);
		return target;
	}

	/**
	 * 解析<set/>标签元素
	 */
	public Set<Object> parseSetElement(Element collectionEle, @Nullable BeanDefinition bd) {
		//获取value-type属性值elementType
		String defaultElementType = collectionEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
		//获取<set/>标签的子标签集合，里面的一个子标签相当于一个set集合元素
		NodeList nl = collectionEle.getChildNodes();
		//创建用于托管Set元素的标记集合类ManagedSet，其中可能包括运行时 bean 引用（要解析为 bean 对象）
		//将会返回该对象作为解析结果
		ManagedSet<Object> target = new ManagedSet<>(nl.getLength());
		target.setSource(extractSource(collectionEle));
		target.setElementTypeName(defaultElementType);
		//设置mergeEnabled属性，表示是否需要合并父bean的集合属性
		target.setMergeEnabled(parseMergeAttribute(collectionEle));
		//调用parseCollectionElements继续解析
		parseCollectionElements(nl, target, bd, defaultElementType);
		return target;
	}

	/**
	 * 解析单value类型的集合标签
	 */
	protected void parseCollectionElements(
			NodeList elementNodes, Collection<Object> target, @Nullable BeanDefinition bd, String defaultElementType) {
		//循环子标签集合
		for (int i = 0; i < elementNodes.getLength(); i++) {
			Node node = elementNodes.item(i);
			//如果属于标签并且不是description标签
			if (node instanceof Element currentElement && !nodeNameEquals(node, DESCRIPTION_ELEMENT)) {
				//那么递归调用parsePropertySubElement解析这些标签，将返回的结果添加到target中
				target.add(parsePropertySubElement(currentElement, bd, defaultElementType));
			}
		}
	}


	/**
	 * 解析<map/>标签元素
	 *
	 * @param bd     BeanDefinition
	 * @param mapEle <map/>标签元素
	 */
	public Map<Object, Object> parseMapElement(Element mapEle, @Nullable BeanDefinition bd) {
		/*
		 * 1 获取、设置相关属性：key-type、value-type、merge，设置到用于托管Map元素的标记集合类ManagedMap中
		 */
		//获取key-type属性值defaultKeyType作为key的默认类型
		String defaultKeyType = mapEle.getAttribute(KEY_TYPE_ATTRIBUTE);
		//获取value-type属性值defaultValueType作为value的默认类型
		String defaultValueType = mapEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
		//获取所有的<entry/>子标签，一个entry表示一个键值对
		List<Element> entryEles = DomUtils.getChildElementsByTagName(mapEle, ENTRY_ELEMENT);
		//创建用于托管Map元素的标记集合类ManagedMap，其中可能包括运行时 bean 引用（要解析为 bean 对象）。
		ManagedMap<Object, Object> map = new ManagedMap<>(entryEles.size());
		//设置相关属性
		map.setSource(extractSource(mapEle));
		map.setKeyTypeName(defaultKeyType);
		map.setValueTypeName(defaultValueType);
		//设置mergeEnabled属性，表示是否需要合并父bean的集合属性
		map.setMergeEnabled(parseMergeAttribute(mapEle));

		/*
		 * 2 遍历所有内部的<entry/>子标签元素，进行解析
		 */
		for (Element entryEle : entryEles) {
			//<entry/>子标签内部只能有一个值标签作为键值对的value，只能有一个<key/>标签作为键值对的key
			NodeList entrySubNodes = entryEle.getChildNodes();
			//记录<key/>标签，同时用于校验重复
			Element keyEle = null;
			//记录value值标签，同时用于校验重复
			Element valueEle = null;
			/*
			 * 2.1 遍历<entry/>子标签的子标签，尝试校验并获取key和value对应的标签
			 *
			 * key对应的<key/>子标签可以没有，但最多有一个
			 * value对应的value值子标签可以没有，但最多有一个
			 */
			for (int j = 0; j < entrySubNodes.getLength(); j++) {
				Node node = entrySubNodes.item(j);
				if (node instanceof Element) {
					//<entry/>标签的子标签
					Element candidateEle = (Element) node;
					//如果是<key/>标签
					if (nodeNameEquals(candidateEle, KEY_ELEMENT)) {
						//如果keyEle不为null，那么说明当前<entry/>标签的子标签出现多个<key/>标签，抛出异常
						if (keyEle != null) {
							error("<entry> element is only allowed to contain one <key> sub-element", entryEle);
						} else {
							//否则记录
							keyEle = candidateEle;
						}
					}
					//如果不是<key/>标签
					else {
						//如果是<description/>标签，那么丢弃
						// Child element is what we're looking for.
						if (nodeNameEquals(candidateEle, DESCRIPTION_ELEMENT)) {
							// the element is a <description> -> ignore it
						}
						//否则，就是表示value的值标签
						// 如果valueEle不为null，那么说明当前<entry/>标签的子标签出现多个值标签，抛出异常
						else if (valueEle != null) {
							error("<entry> element must not contain more than one value sub-element", entryEle);
						} else {
							//否则记录
							valueEle = candidateEle;
						}
					}
				}
			}

			/*
			 * 2.2 解析key的值
			 * 这里就类似于parsePropertyValue方法的逻辑，key的值可能来自于<entry/>标签的key属性、key-ref属性、或者<key/>标签
			 * 在同一个<entry/>标签中只能出现三者的其中一个
			 */
			Object key = null;
			//<entry/>是否设置了key属性
			boolean hasKeyAttribute = entryEle.hasAttribute(KEY_ATTRIBUTE);
			//<entry/>是否设置了key-ref属性
			boolean hasKeyRefAttribute = entryEle.hasAttribute(KEY_REF_ATTRIBUTE);
			//如果key属性、key-ref属性、<key/>标签，这三者同时拥有两个及其之上的元素，那么抛出异常
			if ((hasKeyAttribute && hasKeyRefAttribute) ||
					(hasKeyAttribute || hasKeyRefAttribute) && keyEle != null) {
				error("<entry> element is only allowed to contain either " +
						"a 'key' attribute OR a 'key-ref' attribute OR a <key> sub-element", entryEle);
			}
			/*2.2.1 如果具有key属性，那么肯定没有key-ref属性以及<key/>子标签*/
			if (hasKeyAttribute) {
				//key封装为一个TypedStringValue对象
				key = buildTypedStringValueForMap(entryEle.getAttribute(KEY_ATTRIBUTE), defaultKeyType, entryEle);
			}
			/*2.2.2 如果具有key-ref属性，那么肯定没有key属性以及<key/>子标签*/
			else if (hasKeyRefAttribute) {
				String refName = entryEle.getAttribute(KEY_REF_ATTRIBUTE);
				if (!StringUtils.hasText(refName)) {
					error("<entry> element contains empty 'key-ref' attribute", entryEle);
				}
				//key封装为一个RuntimeBeanReference对象
				RuntimeBeanReference ref = new RuntimeBeanReference(refName);
				ref.setSource(extractSource(entryEle));
				key = ref;
			}
			/*2.2.3 否则，如果具有<key/>子标签，那么肯定没有key属性以及key-ref属性*/
			else if (keyEle != null) {
				//调用parseKeyElement解析<key/>子标签
				key = parseKeyElement(keyEle, bd, defaultKeyType);
			}
			/*2.2.4 否则，啥都没有，抛出异常*/
			else {
				error("<entry> element must specify a key", entryEle);
			}

			/*
			 * 2.3 解析value的值
			 * 这里就类似于parsePropertyValue方法的逻辑，value的值可能来自于<entry/>标签的value属性、value-ref属性或者其他value值标签
			 * 在同一个<entry/>标签中只能出现三者的其中一个
			 */

			// Extract value from attribute or sub-element.
			Object value = null;
			//<entry/>是否设置了value属性
			boolean hasValueAttribute = entryEle.hasAttribute(VALUE_ATTRIBUTE);
			//<entry/>是否设置了value-ref属性
			boolean hasValueRefAttribute = entryEle.hasAttribute(VALUE_REF_ATTRIBUTE);
			//<entry/>是否设置了value-type属性
			boolean hasValueTypeAttribute = entryEle.hasAttribute(VALUE_TYPE_ATTRIBUTE);
			//如果value属性、value-ref属性、value值标签，这三者同时拥有两个及其之上的元素，那么抛出异常
			if ((hasValueAttribute && hasValueRefAttribute) ||
					(hasValueAttribute || hasValueRefAttribute) && valueEle != null) {
				error("<entry> element is only allowed to contain either " +
						"'value' attribute OR 'value-ref' attribute OR <value> sub-element", entryEle);
			}
			//如果具有value-type属性，并且（具有value-ref属性，或者没有value属性，或者具有value值子标签，那么抛出异常
			//意思就是value-type属性如果被设置了，那么一定和value属性绑定；反过来，如果设置了value属性，那么value-type属性则不一定需要
			if ((hasValueTypeAttribute && hasValueRefAttribute) ||
					(hasValueTypeAttribute && !hasValueAttribute) ||
					(hasValueTypeAttribute && valueEle != null)) {
				error("<entry> element is only allowed to contain a 'value-type' " +
						"attribute when it has a 'value' attribute", entryEle);
			}
			/*2.3.1 如果具有value属性，那么肯定没有value-ref属性以及value值子标签*/
			if (hasValueAttribute) {
				//获取<entry/>的value-type属性
				String valueType = entryEle.getAttribute(VALUE_TYPE_ATTRIBUTE);
				//如果没有设置value-type属性，那么使用外层<map/>标签指定的value-type属性
				if (!StringUtils.hasText(valueType)) {
					valueType = defaultValueType;
				}
				//value封装为一个TypedStringValue对象
				value = buildTypedStringValueForMap(entryEle.getAttribute(VALUE_ATTRIBUTE), valueType, entryEle);
			}
			/*2.3.2 如果具有key-ref属性，那么肯定没有value属性以及value值子标签*/
			else if (hasValueRefAttribute) {
				String refName = entryEle.getAttribute(VALUE_REF_ATTRIBUTE);
				if (!StringUtils.hasText(refName)) {
					error("<entry> element contains empty 'value-ref' attribute", entryEle);
				}
				//value封装为一个RuntimeBeanReference对象
				RuntimeBeanReference ref = new RuntimeBeanReference(refName);
				ref.setSource(extractSource(entryEle));
				value = ref;
			}
			/*2.3.3 否则，如果具有value值子标签，那么肯定没有value属性以及value-ref属性*/
			else if (valueEle != null) {
				//递归调用parsePropertySubElement方法解析value值子标签
				value = parsePropertySubElement(valueEle, bd, defaultValueType);
			}
			/*2.3.4 否则，啥都没有，抛出异常*/
			else {
				error("<entry> element must specify a value", entryEle);
			}
			//解析之后的key、value值添加到ManagedMap中
			// Add final key and value to the Map.
			map.put(key, value);
		}
		//返回ManagedMap对象
		return map;
	}

	/**
	 * Build a typed String value Object for the given raw value.
	 *
	 * @see org.springframework.beans.factory.config.TypedStringValue
	 */
	protected final Object buildTypedStringValueForMap(String value, String defaultTypeName, Element entryEle) {
		try {
			TypedStringValue typedValue = buildTypedStringValue(value, defaultTypeName);
			typedValue.setSource(extractSource(entryEle));
			return typedValue;
		} catch (ClassNotFoundException ex) {
			error("Type class [" + defaultTypeName + "] not found for Map key/value type", entryEle, ex);
			return value;
		}
	}

	/**
	 * Parse a key sub-element of a map element.
	 * 解析<entry/>标签下的<key/>标签
	 *
	 * @param keyEle             <entry/>标签下的<key/>标签
	 * @param bd                 BeanDefinition
	 * @param defaultKeyTypeName 外层标签指定的key-type属性
	 */
	@Nullable
	protected Object parseKeyElement(Element keyEle, @Nullable BeanDefinition bd, String defaultKeyTypeName) {
		//获取<key/>标签的子标签集合
		NodeList nl = keyEle.getChildNodes();
		//记录<key/>标签的子标签，同时用于校验重复
		Element subElement = null;
		//遍历集合
		for (int i = 0; i < nl.getLength(); i++) {
			Node node = nl.item(i);
			if (node instanceof Element currentElement) {
				// Child element is what we're looking for.
				// 同样<key/>标签的子标签只能有一个
				if (subElement != null) {
					error("<key> element must not contain more than one value sub-element", keyEle);
				}
				else {
					subElement = currentElement;
				}
			}
		}
		//如果<key/>标签的子标签为null，那么返回null
		if (subElement == null) {
			return null;
		}
		//递归调用三个参数的parsePropertySubElement方法解析<key/>标签的子标签，使用外层标签指定的key-type属性
		return parsePropertySubElement(subElement, bd, defaultKeyTypeName);
	}

	/**
	 * 解析<props/>标签元素
	 * 比较简单，因为properties集合的key和value只能是String类型，不需要递归解析
	 *
	 * @param propsEle <props/>标签元素
	 */
	public Properties parsePropsElement(Element propsEle) {
		//创建用于托管Properties元素的标记集合类ManagedProperties
		ManagedProperties props = new ManagedProperties();
		props.setSource(extractSource(propsEle));
		//设置mergeEnabled属性，表示是否需要合并父bean的集合属性
		props.setMergeEnabled(parseMergeAttribute(propsEle));

		//获取所有的<prop/>子标签集合，一个prop表示一个键值对
		List<Element> propEles = DomUtils.getChildElementsByTagName(propsEle, PROP_ELEMENT);
		/*
		 * 遍历<prop/>子标签，解析
		 */
		for (Element propEle : propEles) {
			//获取key属性的值
			String key = propEle.getAttribute(KEY_ATTRIBUTE);
			// Trim the text value to avoid unwanted whitespace
			// caused by typical XML formatting.
			//获取<prop/>子标签的值，就是value值，去除前后空格
			String value = DomUtils.getTextValue(propEle).trim();
			//使用TypedStringValue封装key和value的值
			TypedStringValue keyHolder = new TypedStringValue(key);
			keyHolder.setSource(extractSource(propEle));
			TypedStringValue valueHolder = new TypedStringValue(value);
			valueHolder.setSource(extractSource(propEle));
			//解析之后的key、value值添加到ManagedProperties中
			props.put(keyHolder, valueHolder);
		}
		//返回ManagedProperties
		return props;
	}


	/**
	 * 处理集合标签的merge属性，用于与父bean配置的集合合并。
	 */
	public boolean parseMergeAttribute(Element collectionElement) {
		//获取merger属性
		String value = collectionElement.getAttribute(MERGE_ATTRIBUTE);
		//如果是默认值，那么获取外部<beans/>的default-merge属性或者自己继承的属性
		if (isDefaultValue(value)) {
			value = this.defaults.getMerge();
		}
		//如果value等于"true"字符串，那么返回true，表示合并；否则就是false，不合并
		return TRUE_VALUE.equals(value);
	}

	/**
	 * Parse a custom element (outside the default namespace).
	 *
	 * @param ele the element to parse
	 * @return the resulting bean definition
	 */
	@Nullable
	public BeanDefinition parseCustomElement(Element ele) {
		return parseCustomElement(ele, null);
	}

	/**
	 * Parse a custom element (outside the default namespace).
	 * 解析自定义标签元素（在默认命名空间之外的标签）
	 *
	 * @param ele          the element to parse
	 * @param containingBd the containing bean definition (if any)
	 * @return the resulting bean definition
	 */
	@Nullable
	public BeanDefinition parseCustomElement(Element ele, BeanDefinition containingBd) {
		// 1.拿到节点ele的命名空间，例如常见的:
		// <context> 节点对应命名空间: http://www.springframework.org/schema/context
		// <aop> 节点对应命名空间: http://www.springframework.org/schema/aop
		String namespaceUri = getNamespaceURI(ele);
		// 2.拿到命名空间对应的的handler, 例如：http://www.springframework.org/schema/context 对应 ContextNameSpaceHandler
		// 2.1 getNamespaceHandlerResolver: 拿到namespaceHandlerResolver
		// 2.2 resolve: 使用namespaceHandlerResolver解析namespaceUri, 拿到namespaceUri对应的NamespaceHandler
		assert namespaceUri != null;
		NamespaceHandler handler = this.readerContext.getNamespaceHandlerResolver().resolve(namespaceUri);
		if (handler == null) {
			error("Unable to locate Spring NamespaceHandler for XML schema namespace [" + namespaceUri + "]", ele);
			return null;
		}
		// 3.使用拿到的handler解析节点（ParserContext用于存放解析需要的一些上下文信息）
		return handler.parse(ele, new ParserContext(this.readerContext, this, containingBd));
	}

	/**
	 * Decorate the given bean definition through a namespace handler, if applicable.
	 *
	 * @param ele         the current element
	 * @param originalDef the current bean definition
	 * @return the decorated bean definition
	 */
	public BeanDefinitionHolder decorateBeanDefinitionIfRequired(Element ele, BeanDefinitionHolder originalDef) {
		return decorateBeanDefinitionIfRequired(ele, originalDef, null);
	}

	/**
	 * Decorate the given bean definition through a namespace handler, if applicable.
	 *
	 * @param ele          the current element
	 * @param originalDef  the current bean definition
	 * @param containingBd the containing bean definition (if any)
	 * @return the decorated bean definition
	 */
	public BeanDefinitionHolder decorateBeanDefinitionIfRequired(
			Element ele, BeanDefinitionHolder originalDef, @Nullable BeanDefinition containingBd) {

		BeanDefinitionHolder finalDefinition = originalDef;

		// Decorate based on custom attributes first.
		// 首先基于自定义属性进行渲染
		// 也就是bean标签上的属性，也就是node
		// 只有当这个node不属于名称空间beans才会进行渲染，这里就不进去看了
		NamedNodeMap attributes = ele.getAttributes();
		for (int i = 0; i < attributes.getLength(); i++) {
			Node node = attributes.item(i);
			finalDefinition = decorateIfRequired(node, finalDefinition, containingBd);
		}

		// Decorate based on custom nested elements.
		// 然后根据标签内嵌套的子标签进行渲染
		// 这里是不属于名称空间beans的子标签才会进行渲染
		NodeList children = ele.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			Node node = children.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				finalDefinition = decorateIfRequired(node, finalDefinition, containingBd);
			}
		}
		return finalDefinition;
	}

	/**
	 * Decorate the given bean definition through a namespace handler,
	 * if applicable.
	 *
	 * @param node         the current child node
	 * @param originalDef  the current bean definition
	 * @param containingBd the containing bean definition (if any)
	 * @return the decorated bean definition
	 */
	public BeanDefinitionHolder decorateIfRequired(
			Node node, BeanDefinitionHolder originalDef, @Nullable BeanDefinition containingBd) {

		//获取标签的命名空间
		String namespaceUri = getNamespaceURI(node);
		//如果命名空间不为空，并且不是默认的命名空间，才进行下面处理
		if (namespaceUri != null && !isDefaultNamespace(namespaceUri)) {
			//根据命名空间找到对应的处理器
			//命名空间决定了哪些标签能用
			//因为可以通过命名空间找到那些标签的处理器
			//有处理器才能使用标签
			NamespaceHandler handler = this.readerContext.getNamespaceHandlerResolver().resolve(namespaceUri);
			//判断是否找到处理器
			if (handler != null) {
				//用处理器进行修饰
				BeanDefinitionHolder decorated =
						handler.decorate(node, originalDef, new ParserContext(this.readerContext, this, containingBd));
				//修饰的结果不为空，返回结果
				if (decorated != null) {
					return decorated;
				}
			}
			//如果找不到处理器且命名空间是spring的命名空间
			//抛出错误，自定义的标签怎么可能使用spring的命名空间！！！
			else if (namespaceUri.startsWith("http://www.springframework.org/schema/")) {
				error("Unable to locate Spring NamespaceHandler for XML schema namespace [" + namespaceUri + "]", node);
			}
			//其他情况，即没有找到处理器，且又不是Spring的命名空间
			//抛出错误，没有处理器可以解析XML标签
			else {
				// A custom namespace, not to be handled by Spring - maybe "xml:...".
				if (logger.isDebugEnabled()) {
					logger.debug("No Spring NamespaceHandler found for XML schema namespace [" + namespaceUri + "]");
				}
			}
		}
		//如果没有命名空间或者是默认的命名空间，不处理直接返回
		return originalDef;
	}


	@Nullable
	private BeanDefinitionHolder parseNestedCustomElement(Element ele, @Nullable BeanDefinition containingBd) {
		BeanDefinition innerDefinition = parseCustomElement(ele, containingBd);
		if (innerDefinition == null) {
			error("Incorrect usage of element '" + ele.getNodeName() + "' in a nested manner. " +
					"This tag cannot be used nested inside <property>.", ele);
			return null;
		}
		String id = ele.getNodeName() + BeanDefinitionReaderUtils.GENERATED_BEAN_NAME_SEPARATOR +
				ObjectUtils.getIdentityHexString(innerDefinition);
		if (logger.isTraceEnabled()) {
			logger.trace("Using generated bean name [" + id +
					"] for nested custom element '" + ele.getNodeName() + "'");
		}
		return new BeanDefinitionHolder(innerDefinition, id);
	}


	/**
	 * Get the namespace URI for the supplied node.
	 * <p>The default implementation uses {@link Node#getNamespaceURI}.
	 * Subclasses may override the default implementation to provide a
	 * different namespace identification mechanism.
	 *
	 * @param node the node
	 */
	@Nullable
	public String getNamespaceURI(Node node) {
		return node.getNamespaceURI();
	}

	/**
	 * Get the local name for the supplied {@link Node}.
	 * <p>The default implementation calls {@link Node#getLocalName}.
	 * Subclasses may override the default implementation to provide a
	 * different mechanism for getting the local name.
	 *
	 * @param node the {@code Node}
	 */
	public String getLocalName(Node node) {
		return node.getLocalName();
	}

	/**
	 * Determine whether the name of the supplied node is equal to the supplied name.
	 * <p>The default implementation checks the supplied desired name against both
	 * {@link Node#getNodeName()} and {@link Node#getLocalName()}.
	 * <p>Subclasses may override the default implementation to provide a different
	 * mechanism for comparing node names.
	 *
	 * @param node        the node to compare
	 * @param desiredName the name to check for
	 */
	public boolean nodeNameEquals(Node node, String desiredName) {
		return desiredName.equals(node.getNodeName()) || desiredName.equals(getLocalName(node));
	}

	/**
	 * Determine whether the given URI indicates the default namespace.
	 */
	public boolean isDefaultNamespace(@Nullable String namespaceUri) {
		return !StringUtils.hasLength(namespaceUri) || BEANS_NAMESPACE_URI.equals(namespaceUri);
	}

	/**
	 * Determine whether the given node indicates the default namespace.
	 */
	public boolean isDefaultNamespace(Node node) {
		return isDefaultNamespace(getNamespaceURI(node));
	}

	private boolean isDefaultValue(String value) {
		return !StringUtils.hasLength(value) || DEFAULT_VALUE.equals(value);
	}

	private boolean isCandidateElement(Node node) {
		return (node instanceof Element && (isDefaultNamespace(node) || !isDefaultNamespace(node.getParentNode())));
	}

}
