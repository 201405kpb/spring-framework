/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.transaction.annotation;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Strategy implementation for parsing Spring's {@link Transactional} annotation.
 *
 * @author Juergen Hoeller
 * @author Mark Paluch
 * @since 2.5
 */
@SuppressWarnings("serial")
public class SpringTransactionAnnotationParser implements TransactionAnnotationParser, Serializable {

	@Override
	public boolean isCandidateClass(Class<?> targetClass) {
		/*
		 * 确定给定的类是否是携带指定注解的候选者(在类型、方法或字段级别)。
		 *
		 * 如果任何一个注解的全路径名都不是以"java."开始，并且该Class全路径名以"start."开始或者Class的类型为Ordered.class，
		 * 那么返回false，否则其他情况都返回true。因此，基本上都会返回true
		 */
		return AnnotationUtils.isCandidateClass(targetClass, Transactional.class);
	}


	/**
	 * 解析方法或者类上的@Transactional注解并解析为一个TransactionAttribute返回
	 *
	 * @param element 方法或者类元数据
	 * @return TransactionAttribute，可能为null
	 */
	@Override
	@Nullable
	public TransactionAttribute parseTransactionAnnotation(AnnotatedElement element) {
		//查找方法或者类上的@Transactional注解，这里会递归的进行向上查找
		//如果在当前方法/类上没找到，那么会继续在父类/接口的方法/类上查找，最终将使用找到的第一个注解数据
		//这就是在接口方法或者接口上的事务注解也能生效的原因，但是它们的优先级更低
		AnnotationAttributes attributes = AnnotatedElementUtils.findMergedAnnotationAttributes(
				element, Transactional.class, false, false);
		//如果找到了@Transactional注解
		if (attributes != null) {
			//解析注解属性，封装为一个TransactionAttribute并返回，实际类型为RuleBasedTransactionAttribute
			return parseTransactionAnnotation(attributes);
		} else {
			//返回null
			return null;
		}
	}

	public TransactionAttribute parseTransactionAnnotation(Transactional ann) {
		return parseTransactionAnnotation(AnnotationUtils.getAnnotationAttributes(ann, false, false));
	}

	/**
	 * 解析注解的各种属性，封装到一个RuleBasedTransactionAttribute对象中
	 * @param attributes 注解
	 * @return RuleBasedTransactionAttribute
	 */
	protected TransactionAttribute parseTransactionAnnotation(AnnotationAttributes attributes) {
		//最终会创建一个RuleBasedTransactionAttribute
		RuleBasedTransactionAttribute rbta = new RuleBasedTransactionAttribute();

		//设置各种属性
		Propagation propagation = attributes.getEnum("propagation");
		rbta.setPropagationBehavior(propagation.value());
		Isolation isolation = attributes.getEnum("isolation");
		rbta.setIsolationLevel(isolation.value());
		rbta.setTimeout(attributes.getNumber("timeout").intValue());
		rbta.setReadOnly(attributes.getBoolean("readOnly"));
		rbta.setQualifier(attributes.getString("value"));

		//设置回滚规则
		//同样，回滚规则在前，不回滚规则在后，如果回滚和不回滚设置了相同的异常，那么抛出异常时将会回滚
		List<RollbackRuleAttribute> rollbackRules = new ArrayList<>();
		//rollbackFor在最前
		for (Class<?> rbRule : attributes.getClassArray("rollbackFor")) {
			rollbackRules.add(new RollbackRuleAttribute(rbRule));
		}
		//rollbackForClassName在第二
		for (String rbRule : attributes.getStringArray("rollbackForClassName")) {
			rollbackRules.add(new RollbackRuleAttribute(rbRule));
		}
		//noRollbackFor在第三
		for (Class<?> rbRule : attributes.getClassArray("noRollbackFor")) {
			rollbackRules.add(new NoRollbackRuleAttribute(rbRule));
		}
		//noRollbackForClassName在最后
		for (String rbRule : attributes.getStringArray("noRollbackForClassName")) {
			rollbackRules.add(new NoRollbackRuleAttribute(rbRule));
		}
		rbta.setRollbackRules(rollbackRules);

		return rbta;
	}



	@Override
	public boolean equals(@Nullable Object other) {
		return (other instanceof SpringTransactionAnnotationParser);
	}

	@Override
	public int hashCode() {
		return SpringTransactionAnnotationParser.class.hashCode();
	}

}
