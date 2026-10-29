-- 独立演示数据源：只放虚构业务表与参考表，不预置任何历史报告
-- 刻意保留可控空值，便于演示空值/唯一性等检测能命中
USE `dq_demo`;

DROP TABLE IF EXISTS `dq_customer`;
CREATE TABLE `dq_customer` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_no` varchar(32) DEFAULT NULL COMMENT '客户编号',
  `customer_name` varchar(50) DEFAULT NULL COMMENT '客户姓名',
  `age` int(11) DEFAULT NULL COMMENT '年龄',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `city` varchar(50) DEFAULT NULL COMMENT '城市',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间，供及时性检测使用',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示-客户表';

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

-- 参考表：仅供准确性类规则演示，内容为非权威虚构数据
DROP TABLE IF EXISTS `ref_city`;
CREATE TABLE `ref_city` (
  `city_name` varchar(50) NOT NULL COMMENT '城市名称',
  PRIMARY KEY (`city_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示-城市参考表';

INSERT INTO `ref_city` (`city_name`) VALUES ('北京'), ('上海'), ('广州'), ('深圳'), ('杭州'), ('南京'), ('武汉'), ('成都');
