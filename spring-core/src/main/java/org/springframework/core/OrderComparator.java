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

package org.springframework.core;

import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * {@link Comparator} implementation for {@link Ordered} objects, sorting
 * by order value ascending, respectively by priority descending.
 * <p>有序对象的比较实现，按顺序值升序或优先级降序排序.</p>
 * <h3>{@code PriorityOrdered} Objects</h3>
 * <h3>PriorityOrderd对象</h3>
 * <p>{@link PriorityOrdered} objects will be sorted with higher priority than
 * <em>plain</em> {@code Ordered} objects.
 * <p>PriorityOrdered对象的优先级将普通Ordered对象高</p>
 *
 * <h3>Same Order Objects</h3>
 * <h3>一些Order对象</h3>
 * <p>Objects that have the same order value will be sorted with arbitrary
 * ordering with respect to other objects with the same order value.
 * <p>具有相同顺序值得对象将相对于相同顺序值得其他对象以任意顺序进行排序</p>
 *
 * <h3>Non-ordered Objects</h3>
 * <h3>无顺序对象</h3>
 * <p>Any object that does not provide its own order value is implicitly
 * assigned a value of {@link Ordered#LOWEST_PRECEDENCE}, thus ending up
 * at the end of a sorted collection in arbitrary order with respect to
 * other objects with the same order value.
 * <p>任何不提供自己的顺序值得对象都将隐式分配一个Ordered.LOWEST_PRECEENCE值,
 * 从而相对于具有相同顺序值的其他对象,该对象以任意顺序结束于排序集合的末尾</p>
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 07.04.2003
 * @see Ordered
 * @see PriorityOrdered
 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
 * @see java.util.List#sort(java.util.Comparator)
 * @see java.util.Arrays#sort(Object[], java.util.Comparator)
 */
public class OrderComparator implements Comparator<Object> {

	/**
	 * Shared default instance of {@code OrderComparator}.
	 * OrderComparator的共享默认实例
	 */
	public static final OrderComparator INSTANCE = new OrderComparator();


	/**
	 * Build an adapted order comparator with the given source provider.
	 * <p>与给定的源提供商建立一个适合的Order比较器</p>
	 * @param sourceProvider the order source provider to use -- 要使用的Order源提供者
	 * @return the adapted comparator -- 适合的比较器
	 * @since 4.1
	 * @see #doCompare(Object, Object, OrderSourceProvider)
	 */
	public Comparator<Object> withSourceProvider(OrderSourceProvider sourceProvider) {
		return (o1, o2) -> doCompare(o1, o2, sourceProvider);
	}

	@Override
	public int compare(@Nullable Object o1, @Nullable Object o2) {
		return doCompare(o1, o2, null);
	}

	/**
	 * 从sourceProvider中获取o1,o2的源对象的优先级值来比较，如果获取不到源对象时，直接从o1,o2中
	 * 获取优先级值进行比较
	 * <ol>
	 *  <li>o1是否属于PriorityOrdered实例的标记【变量 p1】</li>
	 *  <li>o2是否属于PriorityOrdered实例的标记【变量 p2】</li>
	 *  <li>如果o1是PriorityOrdered但o2不是,返回-1。表示o1小于o2,o1要排在o2的前面</li>
	 *  <li>如果o2是PriorityOrdered但o1不是,返回1。表示o1大于o2,o2要排在o1的前面</li>
	 *  <li>获取从sourceProvider中获取o1的源对象的优先级值【变量 i1】</li>
	 *  <li>获取从sourceProvider中获取o2的源对象的优先级值【变量 i2】</li>
	 *  <li>比较优先级值并结果值返回出去</li>
	 * </ol>
	 * @param o1 要比较的对象1
	 * @param o2 要比较的对象2
	 * @param sourceProvider 源对象提供者
	 * @return 优先级值的比较结果
	 */
	private int doCompare(@Nullable Object o1, @Nullable Object o2, @Nullable OrderSourceProvider sourceProvider) {
		// 判断o1是否实现了PriorityOrdered接口
		boolean p1 = (o1 instanceof PriorityOrdered);
		// 判断o2是否实现了PriorityOrdered接口
		boolean p2 = (o2 instanceof PriorityOrdered);
		// 1.如果o1实现了PriorityOrdered接口, 而o2没有, 则o1排前面
		if (p1 && !p2) {
			return -1;
		}
		// 2.如果o2实现了PriorityOrdered接口, 而o1没有, 则o2排前面
		else if (p2 && !p1) {
			return 1;
		}
		// 3.如果o1和o2都实现（都没实现）PriorityOrdered接口
		// Direct evaluation instead of Integer.compareTo to avoid unnecessary object creation.
		// 拿到o1的order值, 如果没实现Ordered接口, 值为Ordered.LOWEST_PRECEDENCE
		int i1 = getOrder(o1, sourceProvider);
		// 拿到o2的order值, 如果没实现Ordered接口, 值为Ordered.LOWEST_PRECEDENCE
		int i2 = getOrder(o2, sourceProvider);
		// 4.通过order值(order值越小, 优先级越高)排序
		return Integer.compare(i1, i2);
	}

	/**
	 * <p>获取从sourceProvider中获取obj的源对象的优先级值，如果获取不到源对象时，直接从o1,o2中
	 * 获取优先级值
	 * <ol>
	 *  <li>定义保存优先级值的变量【变量 order】</li>
	 *  <li>如果obj不为null且sourceProvider不为null:
	 *   <ol>
	 *    <li>获取obj的Order来源【变量 orderSource】</li>
	 *    <li>如果order来源不为null:
	 *     <ol>
	 *      <li>【如果orderSource是数组，会遍历找到第一个有order值的元素，而剩下的元素即使有Order值都会忽略】:
	 *       <ol>
	 *        <li>如果orderSource是数组:
	 *         <ol>
	 *          <li>将orderSource转换成数组对象【变量 sources】</li>
	 *          <li>遍历源对象sources,元素为source:
	 *           <ol>
	 *            <li>获取obj的order值并赋值给order</li>
	 *            <li>如果order不为null,跳出循环</li>
	 *           </ol>
	 *          </li>
	 *         </ol>
	 *        </li>
	 *       </ol>
	 *      </li>
	 *      <li>获取orderSource的order值</li>
	 *     </ol>
	 *    </li>
	 *   </ol>
	 *  </li>
	 *  <li>如果order有值，就返回order；否则 再尝试obj的优先级值并将结果返回出去</li>
	 * </ol>
	 * </p>
	 * Determine the order value for the given object.
	 * <p>确定给定对象的Order值</p>
	 * <p>The default implementation checks against the given {@link OrderSourceProvider}
	 * using {@link #findOrder} and falls back to a regular {@link #getOrder(Object)} call.
	 * <p>默认实现使用findOrder对照给定OrderComparator.OrderSourceProvider进行检查,并回退
	 * 到常规的getOrder(Object)调用</p>
	 * @param obj the object to check -- 检查对象
	 * @return the order value, or {@code Ordered.LOWEST_PRECEDENCE} as fallback
	 * 		-- order值，或Ordered.LOWEST_PRECEDENCE作为后备
	 */
	private int getOrder(@Nullable Object obj, @Nullable OrderSourceProvider sourceProvider) {
		//定义保存优先级值的变量
		Integer order = null;
		//如果obj不为null且sourceProvider不为null
		if (obj != null && sourceProvider != null) {
			//获取obj的Order来源
			Object orderSource = sourceProvider.getOrderSource(obj);
			//如果order来源不为null
			if (orderSource != null) {
				//如果orderSource是数组，会遍历找到第一个有order值的元素，而剩下的元素即使有Order值都会忽略
				//如果orderSource是数组
				if (orderSource.getClass().isArray()) {
					//将orderSource转换成数组对象
					Object[] sources = ObjectUtils.toObjectArray(orderSource);
					//遍历源对象
					for (Object source : sources) {
						//获取obj的order值
						order = findOrder(source);
						//如果order不为null,跳出循环
						if (order != null) {
							break;
						}
					}
				} else {
					//获取orderSource的order值
					order = findOrder(orderSource);
				}
			}
		}
		//如果order有值，就返回order；否则 再尝试obj的优先级值并将结果返回出去
		return (order != null ? order : getOrder(obj));
	}

	/**
	 * <p>获取obj的优先级值:
	 *  <ol>
	 *   <li>如果obj不为null,获取obj的优先级值</li>
	 *   <li>order有值,返回order</li>
	 *   <li>在没有获取到指定优先级值时，返回最低优先级值</li>
	 *  </ol>
	 * </p>
	 * Determine the order value for the given object.
	 * <p>确定给定对象的优先级值</p>
	 * <p>The default implementation checks against the {@link Ordered} interface
	 * through delegating to {@link #findOrder}. Can be overridden in subclasses.
	 * <p>默认实现通过委派findOrder来检查Ordered接口。可以在子类中覆盖</p>
	 * @param obj the object to check -- 检查对象
	 * @return the order value, or {@code Ordered.LOWEST_PRECEDENCE} as fallback
	 *   -- 优先级值,或 {@code Ordered.LOWEST_PRECEDENCE} 作为后备
	 */
	protected int getOrder(@Nullable Object obj) {
		//如果obj不为null
		if (obj != null) {
			//获取obj的优先级值
			Integer order = findOrder(obj);
			//order有值
			if (order != null) {
				//返回order
				return order;
			}
		}
		//在没有获取到指定优先级值时，返回最低优先级值
		return Ordered.LOWEST_PRECEDENCE;
	}

	/**
	 * <p>获取obj的优先级值，用于供Comparator比较</p>
	 * Find an order value indicated by the given object.
	 * <p>查找给定对象指示的优先级值</p>
	 * <p>The default implementation checks against the {@link Ordered} interface.
	 * Can be overridden in subclasses.
	 * <p>默认实现将检查Ordered接口.可以在子类中覆盖</p>
	 * @param obj the object to check -- 检查对象
	 * @return the order value, or {@code null} if none found
	 * 	-- 优先级值;如果找不到,则为null
	 */
	@Nullable
	protected Integer findOrder(Object obj) {
		//如果obj是Ordered实例,获取obj的优先级值；否则返回null
		return (obj instanceof Ordered ordered ? ordered.getOrder() : null);
	}

	/**
	 * Determine a priority value for the given object, if any.
	 * <p>确定给定对象的优先级值(如果有)</p>
	 * <p>The default implementation always returns {@code null}.
	 * Subclasses may override this to give specific kinds of values a
	 * 'priority' characteristic, in addition to their 'order' semantics.
	 * A priority indicates that it may be used for selecting one object over
	 * another, in addition to serving for ordering purposes in a list/array.
	 * <p>默认实现始终返回null。子类可能会覆盖此属性,以为其特定类型的值提供"优先级"
	 * 特征，此外还具有"order"语义。优先级表示出了用于列表/数组中的排序目的之外，还可以
	 * 用于选择一个对象而不是另一个对象</p>
	 * @param obj the object to check -- 检查对象
	 * @return the priority value, or {@code null} if none
	 *    -- 优先级值；如果没有，则为null
	 * @since 4.1
	 */
	@Nullable
	public Integer getPriority(Object obj) {
		return null;
	}

	/**
	 * Sort the given List with a default OrderComparator.
	 * <p>使用默认的OrderComparator对给定的列表进行排序</p>
	 * <p>Optimized to skip sorting for lists with size 0 or 1,
	 * in order to avoid unnecessary array extraction.
	 * <p>优化后可跳过大小为0或1的列表排序，以避免不必要的数组提取</p>
	 * @param list the List to sort -- 要排序的List
	 * @see java.util.List#sort(java.util.Comparator)
	 */
	public static void sort(List<?> list) {
		//如果list至少有一个元素
		if (list.size() > 1) {
			//使用默认的OrderComparator进行排序
			list.sort(INSTANCE);
		}
	}

	/**
	 * Sort the given array with a default OrderComparator.
	 * <p>使用默认的OrderComparator对给定的数组进行排序</p>
	 * <p>Optimized to skip sorting for lists with size 0 or 1,
	 * in order to avoid unnecessary array extraction.
	 * <p>优化后可跳过大小为0或1的列表排序，以避免不必要的数组提取</p>
	 * @param array the array to sort -- 要排序的数组
	 * @see java.util.Arrays#sort(Object[], java.util.Comparator)
	 */
	public static void sort(Object[] array) {
		//如果list至少有一个元素
		if (array.length > 1) {
			//使用默认的OrderComparator进行排序
			Arrays.sort(array, INSTANCE);
		}
	}

	/**
	 * Sort the given array or List with a default OrderComparator,
	 * if necessary. Simply skips sorting when given any other value.
	 * <p>如果有必要，使用默认的OrderCompatator对给定的数组或列表进行排序。
	 * 给定其他任何值时，只需跳过排序</p>
	 * <p>Optimized to skip sorting for lists with size 0 or 1,
	 * in order to avoid unnecessary array extraction.
	 * <p>优化后可跳过大小为0或1的列表排序,以避免不必要的数组提取</p>
	 * @param value the array or List to sort -- 数组或要排序的列表
	 * @see java.util.Arrays#sort(Object[], java.util.Comparator)
	 */
	public static void sortIfNecessary(Object value) {
		//如果value是对象数组
		if (value instanceof Object[]) {
			//使用默认的OrderComparator对value数组进行排序
			sort((Object[]) value);
		}
		//如果value是List对象
		else if (value instanceof List) {
			//使用默认的OrderComparator对value List进行排序
			sort((List<?>) value);
		}
	}


	/**
	 * Strategy interface to provide an order source for a given object.
	 * <>策略接口,用于为给定对象提供订单来源</p>
	 * @since 4.1
	 */
	@FunctionalInterface
	public interface OrderSourceProvider {

		/**
		 * Return an order source for the specified object, i.e. an object that
		 * should be checked for an order value as a replacement to the given object.
		 * <p>返回指定对象的Order来源，即应检查优先级值的对象,以替换给定对象。</p>
		 * <p>Can also be an array of order source objects.
		 * <p>也可以是Order源对象的数组</p>
		 * <p>If the returned object does not indicate any order, the comparator
		 * will fall back to checking the original object.
		 * <p>如果返回的对象没有任何顺序，则比较器将退回到检查原始对象的位置</p>
		 * @param obj the object to find an order source for
		 *            -- 查找Order来源的对象
		 * @return the order source for that object, or {@code null} if none found
		 *   -- 该对象的订单来源，如果找不到，则为{@code null}
		 */
		@Nullable
		Object getOrderSource(Object obj);
	}

}
