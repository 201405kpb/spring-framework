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

package org.springframework.transaction.interceptor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.springframework.lang.Nullable;

/**
 * TransactionAttribute implementation that works out whether a given exception
 * should cause transaction rollback by applying a number of rollback rules,
 * both positive and negative. If no custom rollback rules apply, this attribute
 * behaves like DefaultTransactionAttribute (rolling back on runtime exceptions).
 *
 * <p>{@link TransactionAttributeEditor} creates objects of this class.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 09.04.2003
 * @see TransactionAttributeEditor
 */
@SuppressWarnings("serial")
public class RuleBasedTransactionAttribute extends DefaultTransactionAttribute implements Serializable {

	/**
	 * Prefix for rollback-on-exception rules in description strings.
	 *回滚异常字符串的前缀
	 * */
	public static final String PREFIX_ROLLBACK_RULE = "-";

	/**
	 * Prefix for commit-on-exception rules in description strings.
	 * 不回滚异常字符串的前缀
	 * */
	public static final String PREFIX_COMMIT_RULE = "+";


	/**
	 * 回滚规则的属性集合
	 * 根据parseAttributeSource方法的逻辑，回滚规则在集合前面，不回滚规则在集合后面
	 */
	@Nullable
	private List<RollbackRuleAttribute> rollbackRules;


	/**
	 * Create a new RuleBasedTransactionAttribute, with default settings.
	 * Can be modified through bean property setters.
	 * @see #setPropagationBehavior
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 * @see #setName
	 * @see #setRollbackRules
	 */
	public RuleBasedTransactionAttribute() {
		super();
	}

	/**
	 * Copy constructor. Definition can be modified through bean property setters.
	 * @see #setPropagationBehavior
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 * @see #setName
	 * @see #setRollbackRules
	 */
	public RuleBasedTransactionAttribute(RuleBasedTransactionAttribute other) {
		super(other);
		this.rollbackRules = (other.rollbackRules != null ? new ArrayList<>(other.rollbackRules) : null);
	}

	/**
	 * Create a new DefaultTransactionAttribute with the given
	 * propagation behavior. Can be modified through bean property setters.
	 * @param propagationBehavior one of the propagation constants in the
	 * TransactionDefinition interface
	 * @param rollbackRules the list of RollbackRuleAttributes to apply
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 */
	public RuleBasedTransactionAttribute(int propagationBehavior, List<RollbackRuleAttribute> rollbackRules) {
		super(propagationBehavior);
		this.rollbackRules = rollbackRules;
	}


	/**
	 * Set the list of {@code RollbackRuleAttribute} objects
	 * (and/or {@code NoRollbackRuleAttribute} objects) to apply.
	 * @see RollbackRuleAttribute
	 * @see NoRollbackRuleAttribute
	 */
	public void setRollbackRules(List<RollbackRuleAttribute> rollbackRules) {
		this.rollbackRules = rollbackRules;
	}

	/**
	 * Return the list of {@code RollbackRuleAttribute} objects
	 * (never {@code null}).
	 */
	public List<RollbackRuleAttribute> getRollbackRules() {
		if (this.rollbackRules == null) {
			this.rollbackRules = new ArrayList<>();
		}
		return this.rollbackRules;
	}


	/**
	 * Winning rule is the shallowest rule (that is, the closest in the
	 * inheritance hierarchy to the exception). If no rule applies (-1),
	 * return false.
	 * 采用"Winning rule"机制来判断对当前异常是否需要进行回滚
	 * @see TransactionAttribute#rollbackOn(java.lang.Throwable)
	 */
	@Override
	public boolean rollbackOn(Throwable ex) {
		//rollbackRules中最匹配的回滚规则，默认为null
		RollbackRuleAttribute winner = null;
		//回滚回滚规则匹配成功时的匹配异常栈深度，用来查找最匹配的那一个回滚规则
		int deepest = Integer.MAX_VALUE;

		//如果rollbackRules回滚规则集合不为null，那么判断回滚规则是否匹配
		if (this.rollbackRules != null) {
			//从前向后遍历，因此回滚规则RollbackRuleAttribute将会先进行匹配，不回滚规则NoRollbackRuleAttribute将会后进行匹配
			//NoRollbackRuleAttribute是RollbackRuleAttribute的子类
			for (RollbackRuleAttribute rule : this.rollbackRules) {
				//根据当前规则获取匹配时的异常栈深度
				int depth = rule.getDepth(ex);
				//如果匹配了当前规则，并且当前的深度小于此前匹配的异常栈深度
				if (depth >= 0 && depth < deepest) {
					//那么deepest赋值为当前异常栈深度，即找到最匹配的那一个
					deepest = depth;
					//winner设置为当前回滚规则实例
					winner = rule;
				}
			}
		}

		// User superclass behavior (rollback on unchecked) if no rule matches.
		//rollbackRules匹配完毕如果winner还是为null，那么说明没有任何匹配，此时调用父类的方法和逻辑
		//即如果当前异常属于RuntimeException或者Error级别的异常时，事务才会回滚
		if (winner == null) {
			return super.rollbackOn(ex);
		}

		//判断当前winner是否不属于NoRollbackRuleAttribute
		//如果不属于，那么最终返回true，表示需要回滚；如果属于，那么最终返回false，表示不需要回滚
		return !(winner instanceof NoRollbackRuleAttribute);
	}


	@Override
	public String toString() {
		StringBuilder result = getAttributeDescription();
		if (this.rollbackRules != null) {
			for (RollbackRuleAttribute rule : this.rollbackRules) {
				//如果是回滚规则，则使用"-"前缀，如果是不回滚规则，则使用"+"前缀
				String sign = (rule instanceof NoRollbackRuleAttribute ? PREFIX_COMMIT_RULE : PREFIX_ROLLBACK_RULE);
				result.append(',').append(sign).append(rule.getExceptionName());
			}
		}
		return result.toString();
	}

}
