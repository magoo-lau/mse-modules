package vip.mseapp.data.engine;

/**
 * 源端列元信息（DataReader 描述列，DataWriter 据此生成目标 DDL）。
 */
public record ColumnMeta(String name, int jdbcType, int precision, int scale) {
}
