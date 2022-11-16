package com.kpb.spring.aop;

public interface IAccountService {
	/**
	 * 模拟登陆账户
	 */
	void saveAccount();

	/**
	 * 模拟更新账户
	 * @param id
	 */
	void updateAccount(int id);

	/**
	 * 模拟删除账户
	 * @return
	 */
	int deleteAccount();
}
