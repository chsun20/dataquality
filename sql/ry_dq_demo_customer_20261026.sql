-- ----------------------------
-- 数据质量演示表（空值检测用）
-- 适用：ry-cloud 库
-- 日期：2026-10-26
-- 说明：刻意制造可控空值，便于验证规则命中与报告统计
--   age 为空       : C002(C002 李四/5) C005 C007 C010  -> 4 行
--   customer_name 为空 : C003 C007                      -> 2 行
--   phone 为空     : C004 C005                          -> 2 行
--   email 为空     : C009                               -> 1 行
--   city 为空      : C006                               -> 1 行
-- ----------------------------

-- mysql 客户端默认字符集可能是 latin1，显式声明避免中文注释与数据被二次编码
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `dq_customer`;
CREATE TABLE `dq_customer` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_no` varchar(32) DEFAULT NULL COMMENT '客户编号',
  `customer_name` varchar(50) DEFAULT NULL COMMENT '客户姓名',
  `age` int(11) DEFAULT NULL COMMENT '年龄',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `city` varchar(50) DEFAULT NULL COMMENT '城市',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量演示-客户表';

INSERT INTO `dq_customer` (`customer_no`, `customer_name`, `age`, `phone`, `email`, `city`) VALUES
('C001', '张三',   28,   '13800138001', 'zhangsan@example.com', '北京'),
('C002', '李四',   NULL, '13800138002', 'lisi@example.com',     '上海'),
('C003', NULL,     35,   '13800138003', 'wangwu@example.com',   '广州'),
('C004', '赵六',   42,   NULL,          'zhaoliu@example.com',  '深圳'),
('C005', '钱七',   NULL, NULL,          'qianqi@example.com',   '杭州'),
('C006', '孙八',   25,   '13800138006', 'sunba@example.com',    NULL),
('C007', NULL,     NULL, '13800138007', 'zhoujiu@example.com',  '成都'),
('C008', '吴十',   31,   '13800138008', 'wushi@example.com',    '武汉'),
('C009', '郑十一', 29,   '13800138009', NULL,                   '西安'),
('C010', '王十二', NULL, '13800138010', 'wangshier@example.com','南京');
