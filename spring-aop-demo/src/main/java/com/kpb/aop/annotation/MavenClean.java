package com.kpb.aop.annotation;

import java.io.File;
import java.util.Objects;

public class MavenClean {
	/**
	 * <p>
	 * 根据文件目录的file,删除目录下的所有.lastUpdated文件
	 * </p>
	 *
	 * @param file 一般是maven仓库的路径
	 */
	public static void removeLastUpdatedFile(File file) {
		if (Objects.isNull(file)) {
			return;
		}
		if (!file.isDirectory() || !file.exists()) {
			throw new NullPointerException("路径不存在或路径不是文件夹！");
		}
		File[] arr = file.listFiles(); //获取文件下的所有file
		for (int i = 0; i < arr.length; i++) {
			File fi = arr[i];
			if (fi.isDirectory()) { //递归判断文件,是文件就递归调用
				removeLastUpdatedFile(fi);
			}
			if (fi.getName().contains(".lastUpdated")) {//如果包含.lastUpdated进行删除
				System.out.println(fi.getPath());
				fi.delete();
			}
		}
	}

	public static void main(String[] args) {
		File file = new File("D:\\mavenlib\\libs");
		removeLastUpdatedFile(file);
	}
}