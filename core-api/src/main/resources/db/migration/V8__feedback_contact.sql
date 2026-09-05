-- V8: cms_feedback 新增可选联系方式 email / phone（回访用，原样存用户输入，不结构化）。
ALTER TABLE cms_feedback ADD COLUMN email varchar(320);
ALTER TABLE cms_feedback ADD COLUMN phone varchar(32);
