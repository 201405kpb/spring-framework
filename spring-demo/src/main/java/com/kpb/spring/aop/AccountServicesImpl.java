package com.kpb.spring.aop;

public class AccountServicesImpl implements IAccountService {
	public void saveAccount() {
		System.out.println("执行了保存");
	}

	public void updateAccount(int id) {
		System.out.println("执行了更新"+id);
	}

	public int deleteAccount() {
		System.out.println("执行了删除");
		return 0;
	}
}