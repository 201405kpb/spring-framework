package com.kpb.aop.xml;

public class AddFunctionImpl implements AddFunction{
	@Override
	public void delete() {
		System.out.println("delete");
	}

	@Override
	public void insert() {
		System.out.println("insert");
	}
}