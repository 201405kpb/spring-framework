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

import org.springframework.aop.aspectj.*;
import org.springframework.aop.support.DefaultBeanFactoryPointcutAdvisor;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.parsing.ParseState;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link BeanDefinitionParser} for the {@code <aop:config>} tag.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Adrian Colyer
 * @author Mark Fisher
 * @author Ramnivas Laddad
 * @since 2.0
 */
class ConfigBeanDefinitionParser implements BeanDefinitionParser {

	private static final String ASPECT = "aspect";
	private static final String EXPRESSION = "expression";
	private static final String ID = "id";
	private static final String POINTCUT = "pointcut";
	private static final String ADVICE_BEAN_NAME = "adviceBeanName";
	private static final String ADVISOR = "advisor";
	private static final String ADVICE_REF = "advice-ref";
	private static final String POINTCUT_REF = "pointcut-ref";
	private static final String REF = "ref";
	private static final String BEFORE = "before";
	private static final String DECLARE_PARENTS = "declare-parents";
	private static final String TYPE_PATTERN = "types-matching";
	private static final String DEFAULT_IMPL = "default-impl";
	private static final String DELEGATE_REF = "delegate-ref";
	private static final String IMPLEMENT_INTERFACE = "implement-interface";
	private static final String AFTER = "after";
	private static final String AFTER_RETURNING_ELEMENT = "after-returning";
	private static final String AFTER_THROWING_ELEMENT = "after-throwing";
	private static final String AROUND = "around";
	private static final String RETURNING = "returning";
	private static final String RETURNING_PROPERTY = "returningName";
	private static final String THROWING = "throwing";
	private static final String THROWING_PROPERTY = "throwingName";
	private static final String ARG_NAMES = "arg-names";
	private static final String ARG_NAMES_PROPERTY = "argumentNames";
	private static final String ASPECT_NAME_PROPERTY = "aspectName";
	private static final String DECLARATION_ORDER_PROPERTY = "declarationOrder";
	private static final String ORDER_PROPERTY = "order";
	private static final int METHOD_INDEX = 0;
	private static final int POINTCUT_INDEX = 1;
	private static final int ASPECT_INSTANCE_FACTORY_INDEX = 2;

	/**
	 * 用于存储解析的阶段点位
	 * 内部是一个LinkedList集合，可以模拟栈
	 */
	private final ParseState parseState = new ParseState();


	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		//新建一个CompositeComponentDefinition类型的bean定义，名称就是标签名 aop:config 内部保存了多个ComponentDefinition，基于XML的source默认为null
		CompositeComponentDefinition compositeDef =
				new CompositeComponentDefinition(element.getTagName(), parserContext.extractSource(element));
		//存入解析上下文内部的containingComponents集合中，入栈顶
		parserContext.pushContainingComponent(compositeDef);
		/*
		 * 尝试向容器注入或者升级AspectJAwareAdvisorAutoProxyCreator类型的自动代理创建者bean定义，专门用于后续创建AOP代理对象
		 * 这个类还实现了比如BeanClassLoaderAware、BeanFactoryAware、SmartInstantiationAwareBeanPostProcessor、
		 * InstantiationAwareBeanPostProcessor、BeanPostProcessor …… 等一系列的自动回调接口，它们在创建代理对象的过程中非常有用
		 *
		 * <aop:config/>标签使用AspectJAwareAdvisorAutoProxyCreator创建代理，实际上还有很多创建者可以用于创建代理对象
		 * 比如<aop:aspectj-autoproxy/>以及@EnableAspectJAutoProxy使用AnnotationAwareAspectJAutoProxyCreator
		 * <tx:annotation-driven/>以及@EnableTransactionManagement使用InfrastructureAdvisorAutoProxyCreator
		 * 不同的标签或者注解使用不同的创建者，但容器最终只会创建一个bean定义，采用优先级最高的自动代理创建者的类型，我们后面会讲到
		 */
		configureAutoProxyCreator(parserContext, element);
		// 解析 <aop:config> 下的子节点
		List<Element> childElts = DomUtils.getChildElements(element);
		for (Element elt : childElts) {
			//获取子标签本地名称，即去除"aop:"之后的名称
			String localName = parserContext.getDelegate().getLocalName(elt);
			if (POINTCUT.equals(localName)) {
				//处理 <aop:pointcut /> 子标签，解析出 AspectJExpressionPointcut 对象并注册
				parsePointcut(elt, parserContext);
			} else if (ADVISOR.equals(localName)) {
				// 处理 <aop:advisor /> 子标签，解析出 DefaultBeanFactoryPointcutAdvisor 对象并注册，了指定 Advice 和 Pointcut（如果有）
				parseAdvisor(elt, parserContext);
			} else if (ASPECT.equals(localName)) {
				//处理 <aop:aspectj /> 子标签，解析出所有的 AspectJPointcutAdvisor 对象并注册，里面包含了 Advice 对象和对应的 Pointcut 对象
				// 同时存在 Pointcut 配置，也会解析出 AspectJExpressionPointcut 对象并注册
				parseAspect(elt, parserContext);
			}
		}
		//出栈并注册，并不是注册到注册表中……，可能什么也不做
		parserContext.popAndRegisterContainingComponent();
		return null;
	}

	/**
	 * Configures the auto proxy creator needed to support the {@link BeanDefinition BeanDefinitions}
	 * created by the '{@code <aop:config/>}' tag. Will force class proxying if the
	 * '{@code proxy-target-class}' attribute is set to '{@code true}'.
	 *
	 * 通过<aop:config/>标签的解析触发调用，尝试配置AspectJAwareAdvisorAutoProxyCreator类型的自动代理创建者的bean定义到容器
	 *
	 * @see AopNamespaceUtils
	 */
	private void configureAutoProxyCreator(ParserContext parserContext, Element element) {
		//调用AopNamespaceUtils.registerAspectJAutoProxyCreatorIfNecessary方法
		AopNamespaceUtils.registerAspectJAutoProxyCreatorIfNecessary(parserContext, element);
	}

	/**
	 * Parses the supplied {@code <advisor>} element and registers the resulting
	 * {@link org.springframework.aop.Advisor} and any resulting {@link org.springframework.aop.Pointcut}
	 * with the supplied {@link BeanDefinitionRegistry}.
	 * 解析<aop:advisor/> 标签，也就是通知器标签，一并解析内部的<aop:pointcut/> 标签，生成bean定义并注册到容器注册表缓存中
	 */
	private void parseAdvisor(Element advisorElement, ParserContext parserContext) {
		// 解析<aop:advisor>节点，最终创建的beanClass为`DefaultBeanFactoryPointcutAdvisor`
		// 另外advice-ref属性必须定义，其与内部属性adviceBeanName对应
		AbstractBeanDefinition advisorDef = createAdvisorBeanDefinition(advisorElement, parserContext);
		//获取id属性的值
		String id = advisorElement.getAttribute(ID);

		try {
			//新建一个AdvisorEntry点位，存入parseState，压栈
			this.parseState.push(new AdvisorEntry(id));
			//通知器bean定义的默认名字设置为id
			String advisorBeanName = id;
			if (StringUtils.hasText(advisorBeanName)) {
				//如果设置了id属性，那么直接将beanName和BeanDefinition注册到registry的缓存中
				parserContext.getRegistry().registerBeanDefinition(advisorBeanName, advisorDef);
			} else {
				//如果没有设置id属性，那么通过DefaultBeanNameGenerator生成beanName，随后同样注册到registry的缓存中，返回生成的beanName
				advisorBeanName = parserContext.getReaderContext().registerWithGeneratedName(advisorDef);
			}
			//解析当前<aop:pointcut/>标签的pointcut或者pointcut-ref属性，获取切入点可能是一个切入点bean定义或者一个切入点bean定义的id
			Object pointcut = parsePointcutProperty(advisorElement, parserContext);
			//如果是一个切入点bean定义，那么表示设置了pointcut属性，返回的就是根据切入点表达式创建的一个切入点bean定义
			if (pointcut instanceof BeanDefinition pointcutBeanDefinition) {
				//为bean定义设置pointcut属性，值就是解析后的切入点bean定义
				advisorDef.getPropertyValues().add(POINTCUT, pointcut);
				//注册组件，这里的注册是指存放到外层方法新建的CompositeComponentDefinition对象的内部集合中或者广播事件，而不是注册到注册表中
				parserContext.registerComponent(
						new AdvisorComponentDefinition(advisorBeanName, advisorDef, pointcutBeanDefinition));
			}
			//如果是一个字符串，那么表示设置了pointcut-ref属性，返回的就是该属性的值，表示引入的其他切入点bean定义的id
			else if (pointcut instanceof String strPointCut) {
				//为bean定义设置pointcut属性，值就是pointcut-ref属性的值封装的一个RuntimeBeanReference，将会在运行时解析
				advisorDef.getPropertyValues().add(POINTCUT, new RuntimeBeanReference(strPointCut));
				// 注册组件
				parserContext.registerComponent(
						new AdvisorComponentDefinition(advisorBeanName, advisorDef));
			}
		} finally {
			//AdvisorEntry点位，出栈
			this.parseState.pop();
		}
	}

	/**
	 * Create a {@link RootBeanDefinition} for the advisor described in the supplied. Does <strong>not</strong>
	 * parse any associated '{@code pointcut}' or '{@code pointcut-ref}' attributes.
	 * 创建beanClass类型为DefaultBeanFactoryPointcutAdvisor的bean定义，用于描述<aop:advisor/> 通知器标签
	 */
	private AbstractBeanDefinition createAdvisorBeanDefinition(Element advisorElement, ParserContext parserContext) {
		//新建RootBeanDefinition类型的bean定义，beanClass类型为DefaultBeanFactoryPointcutAdvisor
		RootBeanDefinition advisorDefinition = new RootBeanDefinition(DefaultBeanFactoryPointcutAdvisor.class);
		//设置源
		advisorDefinition.setSource(parserContext.extractSource(advisorElement));
		//获取advice-ref属性的值，advice-ref可以传递一个id指向一个<tx:advice/>标签，用来管理事务
		//或者可以传递一个id或者name，指向一个实现了Advice接口的bean定义
		String adviceRef = advisorElement.getAttribute(ADVICE_REF);
		//如果没有设置这个属性，那就抛出异常："'advice-ref' attribute contains empty value."
		if (!StringUtils.hasText(adviceRef)) {
			parserContext.getReaderContext().error(
					"'advice-ref' attribute contains empty value.", advisorElement, this.parseState.snapshot());
		} else {
			//为bean定义设置adviceBeanName属性，值就是advice-ref属性的值封装的一个RuntimeBeanNameReference，将会在运行时解析
			advisorDefinition.getPropertyValues().add(
					ADVICE_BEAN_NAME, new RuntimeBeanNameReference(adviceRef));
		}
		//如果设置了order属性
		if (advisorElement.hasAttribute(ORDER_PROPERTY)) {
			//那么为bean定义设置order属性，值就是order属性的值
			advisorDefinition.getPropertyValues().add(
					ORDER_PROPERTY, advisorElement.getAttribute(ORDER_PROPERTY));
		}
		//返回bean定义
		return advisorDefinition;
	}

	/**
	 * 解析<aop:aspect/>标签，也就是切面标签，一并解析内部的子标签，生成bean定义并注册到容器注册表缓存中
	 * @param aspectElement <aop:aspect/>标签元素
	 * @param parserContext 解析上下文
	 */
	private void parseAspect(Element aspectElement, ParserContext parserContext) {
		//解析<aop:advisor>节点
		String aspectId = aspectElement.getAttribute(ID);
		// aop ref属性，必须配置。代表切面
		String aspectName = aspectElement.getAttribute(REF);

		try {
			//新建一个AspectEntry点位，存入parseState，压栈
			this.parseState.push(new AspectEntry(aspectId, aspectName));
			//<aop:aspect/>标签解析到的bean定义集合
			List<BeanDefinition> beanDefinitions = new ArrayList<>();
			//<aop:aspect/>标签解析到的bean定义引用集合
			List<BeanReference> beanReferences = new ArrayList<>();
			// 解析<aop:aspect>下的declare-parents节点
			// 采用的是DeclareParentsAdvisor作为beanClass加载
			List<Element> declareParents = DomUtils.getChildElementsByTagName(aspectElement, DECLARE_PARENTS);
			for (Element declareParentsElement : declareParents) {
				/*
				 * 通过parseDeclareParents解析<aop:declare-parents/>子标签元素，新建RootBeanDefinition类型的bean定义
				 * beanClass类型为DeclareParentsAdvisor，解析各种属性并赋值，default-impl和delegate-ref属性有且只能由其中一个
				 * 随后将新建的bean定义同样注册到注册表容器中，最后将返回的bean定义加入到beanDefinitions集合中
				 */
				beanDefinitions.add(parseDeclareParents(declareParentsElement, parserContext));
			}

			// We have to parse "advice" and all the advice kinds in one loop, to get the
			// ordering semantics right.
			/*
			 * 2 解析所有advice通知标签，包括<aop:before/>、<aop:after/>、<aop:after-returning/>、
			 * <aop:after-throwing/>、<aop:around/>，并且设置通知顺序
			 */
			//获取所有子节点元素，该方法对于标签之间的空白换行符号/n也会算作一个Node节点 -> DeferredTextImpl
			//对于标签之间被注释的语句也会算作一个Node节点 -> DeferredCommentImpl
			NodeList nodeList = aspectElement.getChildNodes();
			//标志位，判断有没有发现任何通知标签，默认false
			boolean adviceFoundAlready = false;
			//遍历所有子节点元素
			for (int i = 0; i < nodeList.getLength(); i++) {
				//获取每一个节点
				Node node = nodeList.item(i);
				// 是否为advice:before/advice:after/advice:after-returning/advice:after-throwing/advice:around节点
				if (isAdviceNode(node, parserContext)) {
					// 校验aop:aspect必须有ref属性，否则无法对切入点进行观察操作
					if (!adviceFoundAlready) {
						//adviceFoundAlready改为true
						adviceFoundAlready = true;
						//如果<aop:aspect/>标签的ref属性的没有设置值或者是空白字符等无效值
						//那么抛出异常："<aspect> tag needs aspect bean reference via 'ref' attribute when declaring advices."
						if (!StringUtils.hasText(aspectName)) {
							parserContext.getReaderContext().error(
									"<aspect> tag needs aspect bean reference via 'ref' attribute when declaring advices.",
									aspectElement, this.parseState.snapshot());
							return;
						}
						//如果设置了ref属性值，那么包装成为一个RuntimeBeanReference，加入到beanReferences集合中
						beanReferences.add(new RuntimeBeanReference(aspectName));
					}
					// 解析该通知标签，获取生成的通知bean定义，该bean定义已被注册到容器中类，beanClass类型为AspectJPointcutAdvisor
					AbstractBeanDefinition advisorDefinition = parseAdvice(
							aspectName, i, aspectElement, (Element) node, parserContext, beanDefinitions, beanReferences);
					//加入到beanReferences集合中
					beanDefinitions.add(advisorDefinition);
				}
			}

			//创建解析当前<aop:aspect/>标签的AspectComponentDefinition类型的bean定义
			//内部包含了解析出来的全部bean定义和bean引用
			AspectComponentDefinition aspectComponentDefinition = createAspectComponentDefinition(
					aspectElement, aspectId, beanDefinitions, beanReferences, parserContext);
			//存入解析上下文内部的containingComponents集合中，入栈顶
			parserContext.pushContainingComponent(aspectComponentDefinition);

			//获取全部<aop:pointcut/>子标签元素集合
			List<Element> pointcuts = DomUtils.getChildElementsByTagName(aspectElement, POINTCUT);
			for (Element pointcutElement : pointcuts) {
				/*
				 * 调用parsePointcut方法解析 <aop:pointcut/> 标签
				 * 封装成为beanClass类型为AspectJExpressionPointcut类型的bean定义并且注册到IoC容器缓存中
				 * 该方法此前已经讲过了
				 */
				parsePointcut(pointcutElement, parserContext);
			}
			//出栈并注册，并不是注册到注册表中……，可能什么也不做
			parserContext.popAndRegisterContainingComponent();
		} finally {
			//AspectEntry点位，出栈
			this.parseState.pop();
		}
	}

	private AspectComponentDefinition createAspectComponentDefinition(
			Element aspectElement, String aspectId, List<BeanDefinition> beanDefs,
			List<BeanReference> beanRefs, ParserContext parserContext) {

		BeanDefinition[] beanDefArray = beanDefs.toArray(new BeanDefinition[0]);
		BeanReference[] beanRefArray = beanRefs.toArray(new BeanReference[0]);
		Object source = parserContext.extractSource(aspectElement);
		return new AspectComponentDefinition(aspectId, beanDefArray, beanRefArray, source);
	}

	/**
	 * Return {@code true} if the supplied node describes an advice type. May be one of:
	 * '{@code before}', '{@code after}', '{@code after-returning}',
	 * '{@code after-throwing}' or '{@code around}'.
	 *  判断是不是通知标签节点，如果是任何一个通知标签节点元素，那么就返回true，否则返回false
	 */
	private boolean isAdviceNode(Node aNode, ParserContext parserContext) {
		//如果不是标签节点，直接返回null
		if (!(aNode instanceof Element)) {
			return false;
		} else {
			//获取标签节点的本地名称也就是去除"aop:"之后的名称
			String name = parserContext.getDelegate().getLocalName(aNode);
			//如果是任何一个通知标签节点元素，那么就返回true，否则返回false
			return (BEFORE.equals(name) || AFTER.equals(name) || AFTER_RETURNING_ELEMENT.equals(name) ||
					AFTER_THROWING_ELEMENT.equals(name) || AROUND.equals(name));
		}
	}

	/**
	 * Parse a '{@code declare-parents}' element and register the appropriate
	 * DeclareParentsAdvisor with the BeanDefinitionRegistry encapsulated in the
	 * supplied ParserContext.
	 * 解析<aop:declare-parents/>引介增强标签元素，创建beanClass类型为DeclareParentsAdvisor的bean定义并注到容器缓存中
	 */
	private AbstractBeanDefinition parseDeclareParents(Element declareParentsElement, ParserContext parserContext) {
		//新建RootBeanDefinition类型的bean定义，beanClass类型为DeclareParentsAdvisor
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(DeclareParentsAdvisor.class);
		//获取implement-interface和types-matching属性的值，并设置具有索引的bean定义构造器集合的前两位
		builder.addConstructorArgValue(declareParentsElement.getAttribute(IMPLEMENT_INTERFACE));
		builder.addConstructorArgValue(declareParentsElement.getAttribute(TYPE_PATTERN));

		//获取default-impl和delegate-ref属性的值，也就是增强类
		String defaultImpl = declareParentsElement.getAttribute(DEFAULT_IMPL);
		String delegateRef = declareParentsElement.getAttribute(DELEGATE_REF);
		//如果设置了default-impl并且没有设置delegate-ref
		if (StringUtils.hasText(defaultImpl) && !StringUtils.hasText(delegateRef)) {
			//那么将该属性的值加入具有索引的bean定义构造器集合的第三位
			builder.addConstructorArgValue(defaultImpl);
		}
		//如果设置了delegate-ref并且没有设置default-impl
		else if (StringUtils.hasText(delegateRef) && !StringUtils.hasText(defaultImpl)) {
			//那么将该属性的值封装成一个RuntimeBeanReference对象，加入具有索引的bean定义构造器集合的第三位
			builder.addConstructorArgReference(delegateRef);
		}
		//如果同时设置或者没设置这两个属性，那么抛出异常
		else {
			parserContext.getReaderContext().error(
					"Exactly one of the " + DEFAULT_IMPL + " or " + DELEGATE_REF + " attributes must be specified",
					declareParentsElement, this.parseState.snapshot());
		}
		//获取bean定义
		AbstractBeanDefinition definition = builder.getBeanDefinition();
		// 设置数据源
		definition.setSource(parserContext.extractSource(declareParentsElement));
		//<aop:declare-parents/>标签没有id或者name属性，通过DefaultBeanNameGenerator生成beanName
		//随后同样注册到registry的缓存中，返回生成的beanName
		parserContext.getReaderContext().registerWithGeneratedName(definition);
		// 返回Bean 定义
		return definition;
	}

	/**
	 * Parses one of '{@code before}', '{@code after}', '{@code after-returning}',
	 * '{@code after-throwing}' or '{@code around}' and registers the resulting
	 * BeanDefinition with the supplied BeanDefinitionRegistry.
	 * 解析所有advice通知标签，包括<aop:before/>、<aop:after/>、<aop:after-returning/>、<aop:after-throwing/>、
	 * @param aspectName  待绑定的切面名
	 * @param order 排序号
	 * @param aspectElement <aop:aspect>节点
	 * @param adviceElement <aop:advice>节点
	 * @param parserContext 解析节点的上下文对象
	 * @param beanDefinitions 与aspect相关的所有bean对象集合
	 * @param beanReferences  与aspect相关的所有bean引用对象集合
	 * @return the generated advice RootBeanDefinition 生成的通知bean定义
	 **/
	private AbstractBeanDefinition parseAdvice(
			String aspectName, int order, Element aspectElement, Element adviceElement, ParserContext parserContext,
			List<BeanDefinition> beanDefinitions, List<BeanReference> beanReferences) {

		try {
			//新建一个AdviceEntry点位，存入parseState，压栈
			this.parseState.push(new AdviceEntry(parserContext.getDelegate().getLocalName(adviceElement)));

			/**
			 * 1 创建通知方法bean定义，用于获取通知对应的Method对象，在第三步会被用于构造通知bean定义
			 */
			// create the method factory bean
			// 新建RootBeanDefinition类型的bean定义，beanClass类型为MethodLocatingFactoryBean,MethodLocatingFactoryBean实现了FactoryBean接口，是一个方法工厂，专门用于获取通知对应的Method对象
			RootBeanDefinition methodDefinition = new RootBeanDefinition(MethodLocatingFactoryBean.class);
			//设置bean定义的targetBeanName属性，值就是外部<aop:aspect/>标签的ref属性值，也就是引用的通知类bean定义的id
			methodDefinition.getPropertyValues().add("targetBeanName", aspectName);
			//设置bean定义的methodName属性，值就是method属性值
			methodDefinition.getPropertyValues().add("methodName", adviceElement.getAttribute("method"));
			//设置bean定义的synthetic，值为true，这表示它是一个合成的而不是不是由程序本身定义的bean
			methodDefinition.setSynthetic(true);

			/**
			 * 2 创建切面实例类bean定义，用于获取切面实例对象，也就是通知类对象，在第三步会被用于构造通知bean定义
			 */
			//新建RootBeanDefinition类型的bean定义，beanClass类型为SimpleBeanFactoryAwareAspectInstanceFactory
			//实现了AspectInstanceFactory接口，是一个实例工厂，专门用于获取切面实例对象，也就是通知类对象
			// create instance factory definition
			RootBeanDefinition aspectFactoryDef =
					new RootBeanDefinition(SimpleBeanFactoryAwareAspectInstanceFactory.class);
			//设置bean定义的aspectBeanName属性，值就是外部<aop:aspect/>标签的ref属性值，也就是引用的通知类bean定义的id
			aspectFactoryDef.getPropertyValues().add("aspectBeanName", aspectName);
			//设置bean定义的synthetic，值为true，这表示它是一个合成的而不是不是由程序本身定义的bean
			aspectFactoryDef.setSynthetic(true);

			/**
			 * 3 创建advice通知bean定义
			 */
			// register the pointcut
			// 涉及point-cut属性的解析，并结合上述的两个bean最终包装为AbstractAspectJAdvice通知对象
			AbstractBeanDefinition adviceDef = createAdviceDefinition(
					adviceElement, parserContext, aspectName, order, methodDefinition, aspectFactoryDef,
					beanDefinitions, beanReferences);

			/**
			 * 4 创建切入点通知器bean定义
			 */
			// configure the advisor
			//新建RootBeanDefinition类型的bean定义，beanClass类型为AspectJPointcutAdvisor
			RootBeanDefinition advisorDefinition = new RootBeanDefinition(AspectJPointcutAdvisor.class);
			advisorDefinition.setSource(parserContext.extractSource(adviceElement));
			//设置bean定义的构造器参数，值就是上面创建的advice通知bean定义adviceDef
			advisorDefinition.getConstructorArgumentValues().addGenericArgumentValue(adviceDef);
			//如果外部<aop:aspect/>标签元素具有order属性
			if (aspectElement.hasAttribute(ORDER_PROPERTY)) {
				//设置bean定义的order属性，值就是外部<aop:aspect/>标签元素的order属性值
				//用来控制切入点方法的优先级
				advisorDefinition.getPropertyValues().add(
						ORDER_PROPERTY, aspectElement.getAttribute(ORDER_PROPERTY));
			}
			/**
			 * 5 注册通知器bean定义
			 */
			// register the final advisor
			//通过DefaultBeanNameGenerator生成beanName，随后将最终得到的切入点通知器bean定义同样注册到registry的缓存中
			parserContext.getReaderContext().registerWithGeneratedName(advisorDefinition);
			//返回通知器bean定义
			return advisorDefinition;
		} finally {
			//AdviceEntry点位，出栈
			this.parseState.pop();
		}
	}

	/**
	 * Creates the RootBeanDefinition for a POJO advice bean. Also causes pointcut
	 * parsing to occur so that the pointcut may be associate with the advice bean.
	 * This same pointcut is also configured as the pointcut for the enclosing
	 * Advisor definition using the supplied MutablePropertyValues.
	 * 创建一个advice通知bean定义，beanClass类型为该通知标签对应的实现类类型，还会解析内部的切入点，
	 */
	private AbstractBeanDefinition createAdviceDefinition(
			Element adviceElement, ParserContext parserContext, String aspectName, int order,
			RootBeanDefinition methodDef, RootBeanDefinition aspectFactoryDef,
			List<BeanDefinition> beanDefinitions, List<BeanReference> beanReferences) {

		//新建RootBeanDefinition类型的bean定义，beanClass类型为该通知标签对应的实现类类型
		RootBeanDefinition adviceDefinition = new RootBeanDefinition(getAdviceClass(adviceElement, parserContext));
		adviceDefinition.setSource(parserContext.extractSource(adviceElement));
		//设置bean定义的aspectName属性，值就是外部<aop:aspect/>标签的ref属性值，也就是引用的通知类bean定义的id
		adviceDefinition.getPropertyValues().add(ASPECT_NAME_PROPERTY, aspectName);
		//设置bean定义的declarationOrder属性，值就是在当前外部<aop:aspect/>标签中的所有节点的定义顺序由上而下的索引值
		adviceDefinition.getPropertyValues().add(DECLARATION_ORDER_PROPERTY, order);

		//如果具有returning属性，说明是后置通知
		if (adviceElement.hasAttribute(RETURNING)) {
			//设置bean定义的returningName属性，值就是returning属性的值
			adviceDefinition.getPropertyValues().add(
					RETURNING_PROPERTY, adviceElement.getAttribute(RETURNING));
		}
		//如果具有throwing属性，说明是异常通知
		if (adviceElement.hasAttribute(THROWING)) {
			//设置bean定义的throwingName属性，值就是throwing属性的值
			adviceDefinition.getPropertyValues().add(
					THROWING_PROPERTY, adviceElement.getAttribute(THROWING));
		}
		//如果具有arg-names属性，这表示接收目标方法的参数
		if (adviceElement.hasAttribute(ARG_NAMES)) {
			//设置bean定义的argumentNames属性，值就是arg-names属性的值
			adviceDefinition.getPropertyValues().add(
					ARG_NAMES_PROPERTY, adviceElement.getAttribute(ARG_NAMES));
		}

		// 获取构造器参数
		ConstructorArgumentValues cav = adviceDefinition.getConstructorArgumentValues();
		// 设置构造器第一个参数属性，值为methodDef，也就是此前构建的通知方法bean定义
		cav.addIndexedArgumentValue(METHOD_INDEX, methodDef);
		// 解析当前通知标签的pointcut或者pointcut-ref属性，获取切入点，返回值可能是一个切入点bean定义或者一个切入点bean定义的id
		Object pointcut = parsePointcutProperty(adviceElement, parserContext);
		//如果是一个切入点bean定义，那么表示设置了pointcut属性，返回的就是根据切入点表达式创建的一个切入点bean定义
		if (pointcut instanceof BeanDefinition) {
			//为bean定义设置构造器第二个参数属性，值就是解析后的切入点bean定义
			cav.addIndexedArgumentValue(POINTCUT_INDEX, pointcut);
			//加入beanDefinitions集合
			beanDefinitions.add((BeanDefinition) pointcut);
		}
		//如果是一个字符串，那么表示设置了pointcut-ref属性，返回的就是该属性的值，表示引入的其他切入点bean定义的id
		else if (pointcut instanceof String) {
			RuntimeBeanReference pointcutRef = new RuntimeBeanReference((String) pointcut);
			//为bean定义设置构造器第二个参数属性，值就是pointcut-ref属性的值封装的一个RuntimeBeanReference，将会在运行时解析
			cav.addIndexedArgumentValue(POINTCUT_INDEX, pointcutRef);
			//加入beanReferences集合
			beanReferences.add(pointcutRef);
		}
		//为bean定义设置构造器第三个参数属性，值就是切面通知类bean定义，也就是此前构建的切面通知类bean定义
		cav.addIndexedArgumentValue(ASPECT_INSTANCE_FACTORY_INDEX, aspectFactoryDef);
		//返回bean定义
		return adviceDefinition;
	}

	/**
	 * Gets the advice implementation class corresponding to the supplied {@link Element}.
	 * 获取给定通知元素对应的bean定义的beanClass的实现类类型
	 */
	private Class<?> getAdviceClass(Element adviceElement, ParserContext parserContext) {
		//获取该通知标签的本地名称
		String elementName = parserContext.getDelegate().getLocalName(adviceElement);
		//根据不同的通知类型返回不同的Class
		if (BEFORE.equals(elementName)) {
			//如果是<aop:before/>通知，那么返回AspectJMethodBeforeAdvice.class
			return AspectJMethodBeforeAdvice.class;
		} else if (AFTER.equals(elementName)) {
			//如果是<aop:after/>通知，那么返回AspectJAfterAdvice.class
			return AspectJAfterAdvice.class;
		} else if (AFTER_RETURNING_ELEMENT.equals(elementName)) {
			//如果是<aop:after-returning/>通知，那么返回AspectJAfterReturningAdvice.class
			return AspectJAfterReturningAdvice.class;
		} else if (AFTER_THROWING_ELEMENT.equals(elementName)) {
			//如果是<aop:after-throwing/>通知，那么返回AspectJAfterThrowingAdvice.class
			return AspectJAfterThrowingAdvice.class;
		} else if (AROUND.equals(elementName)) {
			//如果是<aop:around/>通知，那么返回AspectJAroundAdvice.class
			return AspectJAroundAdvice.class;
		} else {
			//其他情况，抛出异常
			throw new IllegalArgumentException("Unknown advice kind [" + elementName + "].");
		}
	}

	/**
	 * Parses the supplied {@code <pointcut>} and registers the resulting
	 * Pointcut with the BeanDefinitionRegistry.
	 * 解析<aop:pointcut/> 标签，也就是切入点表达式标签，生成bean定义注册到容器中
	 */
	private AbstractBeanDefinition parsePointcut(Element pointcutElement, ParserContext parserContext) {
		// 切入点的唯一标识
		String id = pointcutElement.getAttribute(ID);
		// 获取切入点的表达式
		String expression = pointcutElement.getAttribute(EXPRESSION);
		//<aop:pointcut/> 标签对应的bean定义
		AbstractBeanDefinition pointcutDefinition = null;

		try {
			// 采用栈保存切入点
			this.parseState.push(new PointcutEntry(id));
			//创建切入点表达式的bean定义对象，bean定义类型为RootBeanDefinition，beanClass类型为AspectJExpressionPointcut
			//scope属性设置为prototype，synthetic属性设置为true，设置expression属性的值为切入点表达式字符串
			pointcutDefinition = createPointcutDefinition(expression);
			//设置源
			pointcutDefinition.setSource(parserContext.extractSource(pointcutElement));
			//切入点bean定义的默认名字设置为id
			String pointcutBeanName = id;
			//如果设置了id属性
			if (StringUtils.hasText(pointcutBeanName)) {
				//注册bean对象
				parserContext.getRegistry().registerBeanDefinition(pointcutBeanName, pointcutDefinition);
			}
			//如果没有设置id属性
			else {
				//那么生成beanName，随后同样注册到registry的缓存中，返回生成的beanName
				pointcutBeanName = parserContext.getReaderContext().registerWithGeneratedName(pointcutDefinition);
			}
			//注册组件，这里的注册是指存放到外层方法新建的CompositeComponentDefinition对象的内部集合中或者广播事件，而不是注册到注册表中
			parserContext.registerComponent(
					new PointcutComponentDefinition(pointcutBeanName, pointcutDefinition, expression));
		} finally {
			// 创建后移除
			this.parseState.pop();
		}
		//返回创建的bean定义
		return pointcutDefinition;
	}

	/**
	 * Parses the {@code pointcut} or {@code pointcut-ref} attributes of the supplied
	 * {@link Element} and add a {@code pointcut} property as appropriate. Generates a
	 * {@link org.springframework.beans.factory.config.BeanDefinition} for the pointcut if  necessary
	 * and returns its bean name, otherwise returns the bean name of the referred pointcut.
	 * 解析<aop:advisor/>标签的pointcut或者pointcut-ref属性，即获取Advisor生成器的Pointcut切入点
	 */
	@Nullable
	private Object parsePointcutProperty(Element element, ParserContext parserContext) {
		//如果当前<aop:advisor/>标签同时具有pointcut和pointcut-ref属性那么抛出异常："Cannot define both 'pointcut' and 'pointcut-ref' on <advisor> tag."
		if (element.hasAttribute(POINTCUT) && element.hasAttribute(POINTCUT_REF)) {
			parserContext.getReaderContext().error(
					"Cannot define both 'pointcut' and 'pointcut-ref' on <advisor> tag.",
					element, this.parseState.snapshot());
			return null;
		}
		//否则，如果具有pointcut属性，那么一定没有pointcut-ref属性
		else if (element.hasAttribute(POINTCUT)) {
			// Create a pointcut for the anonymous pc and register it.
			//获取pointcut属性的值，也就是切入点表达式字符串
			String expression = element.getAttribute(POINTCUT);
			//创建切入点bean定义对象，bean定义类型为RootBeanDefinition，beanClass类型为AspectJExpressionPointcut
			//scope属性设置为prototype，synthetic属性设置为true，设置expression属性的值为切入点表达式字符串
			AbstractBeanDefinition pointcutDefinition = createPointcutDefinition(expression);
			//设置源，属于当前<aop:advisor/>标签元素
			pointcutDefinition.setSource(parserContext.extractSource(element));
			//返回新建的切入点bean定义对象
			return pointcutDefinition;
		}
		//否则，如果具有pointcut-ref属性，那么一定没有pointcut属性
		else if (element.hasAttribute(POINTCUT_REF)) {
			//获取pointcut-ref属性的值，也就是其他地方的<aop:pointcut/> 标签的id，表示引入其他外部切入点
			String pointcutRef = element.getAttribute(POINTCUT_REF);
			//如果是空白字符之类的无意义字符串，那么抛出异常："'pointcut-ref' attribute contains empty value."
			if (!StringUtils.hasText(pointcutRef)) {
				parserContext.getReaderContext().error(
						"'pointcut-ref' attribute contains empty value.", element, this.parseState.snapshot());
				return null;
			}
			//直接返回pointcut-ref属性的值
			return pointcutRef;
		}
		//否则，表示没有设置这两个属性的任何一个，同样抛出异常："Must define one of 'pointcut' or 'pointcut-ref' on <advisor> tag."
		else {
			parserContext.getReaderContext().error(
					"Must define one of 'pointcut' or 'pointcut-ref' on <advisor> tag.",
					element, this.parseState.snapshot());
			return null;
		}
	}

	/**
	 * Creates a {@link BeanDefinition} for the {@link AspectJExpressionPointcut} class using
	 * the supplied pointcut expression.
	 * 使用给定的切入点表达式创建AspectJExpressionPointcut类型的bean定义对象
	 */
	protected AbstractBeanDefinition createPointcutDefinition(String expression) {
		//新建RootBeanDefinition类型的bean定义，beanClass类型为AspectJExpressionPointcut
		RootBeanDefinition beanDefinition = new RootBeanDefinition(AspectJExpressionPointcut.class);
		//设置scope属性为prototype
		beanDefinition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		//设置synthetic属性为true，这表示它是一个合成的而不是不是由程序本身定义的bean
		beanDefinition.setSynthetic(true);
		//添加expression属性值为参数的切入点表达式字符串
		beanDefinition.getPropertyValues().add(EXPRESSION, expression);
		//返回创建的bean定义
		return beanDefinition;
	}

}
