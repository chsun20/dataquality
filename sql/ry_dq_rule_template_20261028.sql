-- ----------------------------
-- 规则模板化改造：记录规则使用的模板与参数，用于编辑回显
-- 日期：2026-10-28
-- ----------------------------

ALTER TABLE `rule`
    ADD COLUMN `template_code` varchar(64) DEFAULT NULL COMMENT '规则模板编码，多模板组合为 mixed' AFTER `type_code`,
    ADD COLUMN `template_params` varchar(2000) DEFAULT NULL COMMENT '模板与参数配置JSON' AFTER `template_code`;
