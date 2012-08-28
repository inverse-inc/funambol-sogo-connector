delete from fnbl_sync_source_type where id='sogo';
insert into fnbl_sync_source_type(id, description, class, admin_class)
values('sogo','SOGo SyncSource','ca.inverse.sogo.engine.source.SOGoSyncSource','ca.inverse.sogo.admin.SOGoSyncSourceConfigPanel');

delete from fnbl_module where id='sogo';
insert into fnbl_module (id, name, description)
values('sogo','sogo','SOGo');

delete from fnbl_connector where id='sogo';
insert into fnbl_connector(id, name, description, admin_class)
values('sogo','FunambolSOGoConnector','Funambol SOGo Connector','');

delete from fnbl_connector_source_type where connector='sogo' and sourcetype='sogo';
insert into fnbl_connector_source_type(connector, sourcetype)
values('sogo','sogo');

delete from fnbl_module_connector where module='sogo' and connector='sogo';
insert into fnbl_module_connector(module, connector)
values('sogo','sogo');