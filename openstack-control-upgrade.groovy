/**
 * Update packages on given nodes
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [http://10.10.10.1:8000].
 *   STAGE_TEST_UPGRADE         Run test upgrade stage (bool)
 *   STAGE_REAL_UPGRADE         Run real upgrade stage (bool)
 *   STAGE_ROLLBACK_UPGRADE     Run rollback upgrade stage (bool)
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()


def saltMaster

node() {

    stage('Connect to Salt API') {
        saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }

    if (STAGE_TEST_UPGRADE.toBoolean() == true) {
        stage('Test upgrade') {


            try {
                salt.enforceState(saltMaster, 'I@salt:master', 'reclass')
            } catch (Exception e) {
                common.warningMsg("Some parts of Reclass state failed. The most probable reasons were uncommited changes. We should continue to run")
            }

            try {
                salt.runSaltProcessStep(saltMaster, '*', 'saltutil.refresh_pillar', [], null, true)
            } catch (Exception e) {
                common.warningMsg("No response from some minions. We should continue to run")
            }

            try {
                salt.runSaltProcessStep(saltMaster, '*', 'saltutil.sync_all', [], null, true)
            } catch (Exception e) {
                common.warningMsg("No response from some minions. We should continue to run")
            }

            def _pillar = salt.getGrain(saltMaster, 'I@salt:master', 'domain')
            def domain = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(domain)

            // read backupninja variable
            _pillar = salt.getPillar(saltMaster, 'I@backupninja:client', '_param:backupninja_backup_host')
            def backupninja_backup_host = _pillar['return'][0].values()[0]
            print(_pillar)
            print(backupninja_backup_host)

            _pillar = salt.getGrain(saltMaster, 'I@salt:control', 'id')
            def kvm01 = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(kvm01)

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:upg01:provider')
            def upgNodeProvider = _pillar['return'][0].values()[0]
            print(_pillar)
            print(upgNodeProvider)


            salt.runSaltProcessStep(saltMaster, "${upgNodeProvider}", 'virt.destroy', ["upg01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${upgNodeProvider}", 'virt.undefine', ["upg01.${domain}"], null, true)


            try {
                salt.cmdRun(saltMaster, 'I@salt:master', "salt-key -d upg01.${domain} -y")
            } catch (Exception e) {
                common.warningMsg("upg01.${domain} does not match any accepted, unaccepted or rejected keys. The key did not exist yet or was already removed. We should continue to run")
            }


            // salt 'kvm02*' state.sls salt.control
            salt.enforceState(saltMaster, "${upgNodeProvider}", 'salt.control')

            // wait until upg node is registered in salt-key
            salt.minionPresent(saltMaster, 'I@salt:master', 'upg01')

            // salt '*' saltutil.refresh_pillar
            salt.runSaltProcessStep(saltMaster, 'upg*', 'saltutil.refresh_pillar', [], null, true)
            // salt '*' saltutil.sync_all
            salt.runSaltProcessStep(saltMaster, 'upg*', 'saltutil.sync_all', [], null, true)

            // salt "upg*" state.sls linux,openssh,salt.minion,ntp,rsyslog
            try {
                salt.enforceState(saltMaster, 'upg*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
            } catch (Exception e) {
                common.warningMsg('Received no response because salt-minion was restarted. We should continue to run')
            }
            salt.enforceState(saltMaster, 'upg*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])

            // salt "upg*" state.sls rabbitmq
            salt.enforceState(saltMaster, 'upg*', ['rabbitmq', 'memcached'])
            try {
                salt.enforceState(saltMaster, 'I@backupninja:client', ['openssh.client', 'salt.minion'])
            } catch (Exception e) {
                common.warningMsg('salt-minion was restarted. We should continue to run')
            }
            try {
                salt.enforceState(saltMaster, 'I@backupninja:server', ['salt.minion'])
            } catch (Exception e) {
                common.warningMsg('salt-minion was restarted. We should continue to run')
            }
            // salt '*' state.apply salt.minion.grains
            //salt.enforceState(saltMaster, '*', 'salt.minion.grains')
            // salt -C 'I@backupninja:server' state.sls backupninja
            salt.enforceState(saltMaster, 'I@backupninja:server', 'backupninja')
            // salt -C 'I@backupninja:client' state.sls backupninja
            salt.enforceState(saltMaster, 'I@backupninja:client', 'backupninja')
            salt.runSaltProcessStep(saltMaster, 'I@backupninja:client', 'ssh.rm_known_host', ["root", "${backupninja_backup_host}"], null, true)
            try {
                salt.cmdRun(saltMaster, 'I@backupninja:client', "arp -d ${backupninja_backup_host}")
            } catch (Exception e) {
                common.warningMsg('The ARP entry does not exist. We should continue to run.')
            }
            salt.runSaltProcessStep(saltMaster, 'I@backupninja:client', 'ssh.set_known_host', ["root", "${backupninja_backup_host}"], null, true)
            salt.cmdRun(saltMaster, 'I@backupninja:client', 'backupninja -n --run /etc/backup.d/101.mysql')
            salt.cmdRun(saltMaster, 'I@backupninja:client', 'backupninja -n --run /etc/backup.d/200.backup.rsync > /tmp/backupninjalog')

            salt.enforceState(saltMaster, 'I@xtrabackup:server', 'xtrabackup')
            salt.enforceState(saltMaster, 'I@xtrabackup:client', 'openssh.client')
            salt.cmdRun(saltMaster, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
            salt.cmdRun(saltMaster, 'I@xtrabackup:client', "su root -c '/usr/local/bin/innobackupex-runner.sh'")

            def databases = salt.cmdRun(saltMaster, 'I@mysql:client','salt-call mysql.db_list | grep upgrade | awk \'/-/ {print \$2}\'')
            if(databases && databases != ""){
                def databasesList = databases['return'][0].values()[0].trim().tokenize("\n")
                for( i = 0; i < databasesList.size(); i++){
                    if(databasesList[i].toLowerCase().contains('upgrade')){
                        salt.runSaltProcessStep(saltMaster, 'I@mysql:client', 'mysql.db_remove', ["${databasesList[i]}"], null, true)
                        common.warningMsg("removing database ${databasesList[i]}")
                        salt.runSaltProcessStep(saltMaster, 'I@mysql:client', 'file.remove', ["/root/mysql/flags/${databasesList[i]}-installed"], null, true)
                    }
                }
                salt.enforceState(saltMaster, 'I@mysql:client', 'mysql.client')
            }else{
                common.errorMsg("No _upgrade databases were returned")
            }

            try {
                salt.enforceState(saltMaster, 'upg*', 'keystone.server')
                salt.runSaltProcessStep(saltMaster, 'upg*', 'service.restart', ['apache2'], null, true)
            } catch (Exception e) {
                common.warningMsg('Restarting Apache2')
                salt.runSaltProcessStep(saltMaster, 'upg*', 'service.restart', ['apache2'], null, true)
            }
            try {
                salt.enforceState(saltMaster, 'upg*', 'keystone.client')
            } catch (Exception e) {
                common.warningMsg('running keystone.client state again')
                salt.enforceState(saltMaster, 'upg*', 'keystone.client')
            }
            try {
                salt.enforceState(saltMaster, 'upg*', 'glance')
            } catch (Exception e) {
                common.warningMsg('running glance state again')
                salt.enforceState(saltMaster, 'upg*', 'glance')
            }
            salt.enforceState(saltMaster, 'upg*', 'keystone.server')
            try {
                salt.enforceState(saltMaster, 'upg*', 'nova')
            } catch (Exception e) {
                common.warningMsg('running nova state again')
                salt.enforceState(saltMaster, 'upg*', 'nova')
            }
            // run nova state again as sometimes nova does not enforce itself for some reason
            try {
                salt.enforceState(saltMaster, 'upg*', 'nova')
            } catch (Exception e) {
                common.warningMsg('running nova state again')
                salt.enforceState(saltMaster, 'upg*', 'nova')
            }
            try {
                salt.enforceState(saltMaster, 'upg*', 'cinder')
            } catch (Exception e) {
                common.warningMsg('running cinder state again')
                salt.enforceState(saltMaster, 'upg*', 'cinder')
            }
            try {
                salt.enforceState(saltMaster, 'upg*', 'neutron')
            } catch (Exception e) {
                common.warningMsg('running neutron state again')
                salt.enforceState(saltMaster, 'upg*', 'neutron')
            }
            try {
                salt.enforceState(saltMaster, 'upg*', 'heat')
            } catch (Exception e) {
                common.warningMsg('running heat state again')
                salt.enforceState(saltMaster, 'upg*', 'heat')
            }
            salt.cmdRun(saltMaster, 'upg01*', '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack volume list; openstack orchestration service list')

            if (STAGE_TEST_UPGRADE.toBoolean() == true && STAGE_REAL_UPGRADE.toBoolean() == true) {
                stage('Ask for manual confirmation') {
                    input message: "Do you want to continue with upgrade?"
                }
            }
        }
    }

    if (STAGE_REAL_UPGRADE.toBoolean() == true) {
        stage('Real upgrade') {
            // # actual upgrade

            _pillar = salt.getGrain(saltMaster, 'I@salt:master', 'domain')
            domain = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(domain)

            _pillar = salt.getGrain(saltMaster, 'I@salt:control', 'id')
            kvm01 = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(kvm01)

            def errorOccured = false

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:ctl01:provider')
            def ctl01NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:ctl02:provider')
            def ctl02NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:ctl03:provider')
            def ctl03NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:prx01:provider')
            def prx01NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:prx02:provider')
            def prx02NodeProvider = _pillar['return'][0].values()[0]


            salt.runSaltProcessStep(saltMaster, "${prx01NodeProvider}", 'virt.destroy', ["prx01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${prx02NodeProvider}", 'virt.destroy', ["prx02.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl01NodeProvider}", 'virt.destroy', ["ctl01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl02NodeProvider}", 'virt.destroy', ["ctl02.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl03NodeProvider}", 'virt.destroy', ["ctl03.${domain}"], null, true)


            try {
                salt.cmdRun(saltMaster, "${prx01NodeProvider}", "[ ! -f /root/prx01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx01.${domain}/system.qcow2 ./prx01.${domain}.qcow2.bak")
            } catch (Exception e) {
                common.warningMsg('File already exists')
            }
            try {
                salt.cmdRun(saltMaster, "${prx02NodeProvider}", "[ ! -f /root/prx02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/prx02.${domain}/system.qcow2 ./prx02.${domain}.qcow2.bak")
            } catch (Exception e) {
                common.warningMsg('File already exists')
            }
            try {
                salt.cmdRun(saltMaster, "${ctl01NodeProvider}", "[ ! -f /root/ctl01.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl01.${domain}/system.qcow2 ./ctl01.${domain}.qcow2.bak")
            } catch (Exception e) {
                common.warningMsg('File already exists')
            }
            try {
                salt.cmdRun(saltMaster, "${ctl02NodeProvider}", "[ ! -f /root/ctl02.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl02.${domain}/system.qcow2 ./ctl02.${domain}.qcow2.bak")
            } catch (Exception e) {
                common.warningMsg('File already exists')
            }
            try {
                salt.cmdRun(saltMaster, "${ctl03NodeProvider}", "[ ! -f /root/ctl03.${domain}.qcow2.bak ] && cp /var/lib/libvirt/images/ctl03.${domain}/system.qcow2 ./ctl03.${domain}.qcow2.bak")
            } catch (Exception e) {
                common.warningMsg('File already exists')
            }


            salt.runSaltProcessStep(saltMaster, "${prx01NodeProvider}", 'virt.undefine', ["prx01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${prx02NodeProvider}", 'virt.undefine', ["prx02.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl01NodeProvider}", 'virt.undefine', ["ctl01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl02NodeProvider}", 'virt.undefine', ["ctl02.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl03NodeProvider}", 'virt.undefine', ["ctl03.${domain}"], null, true)

            salt.cmdRun(saltMaster, 'I@xtrabackup:client', "su root -c '/usr/local/bin/innobackupex-runner.sh'")

            try {
                salt.cmdRun(saltMaster, 'I@salt:master', "salt-key -d ctl01.${domain},ctl02.${domain},ctl03.${domain},prx01.${domain},prx02.${domain} -y")
            } catch (Exception e) {
                common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
            }

            // salt 'kvm*' state.sls salt.control
            salt.enforceState(saltMaster, 'I@salt:control', 'salt.control')

            // wait until ctl and prx nodes are registered in salt-key
            salt.minionPresent(saltMaster, 'I@salt:master', 'ctl01')
            salt.minionPresent(saltMaster, 'I@salt:master', 'ctl02')
            salt.minionPresent(saltMaster, 'I@salt:master', 'ctl03')
            salt.minionPresent(saltMaster, 'I@salt:master', 'prx01')
            salt.minionPresent(saltMaster, 'I@salt:master', 'prx02')


            // salt '*' saltutil.refresh_pillar
            salt.runSaltProcessStep(saltMaster, '*', 'saltutil.refresh_pillar', [], null, true)
            // salt '*' saltutil.sync_all
            salt.runSaltProcessStep(saltMaster, '*', 'saltutil.sync_all', [], null, true)

            try {
                salt.enforceState(saltMaster, 'ctl* or prx* or ctl*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])
            } catch (Exception e) {
                common.warningMsg('Received no response because salt-minion was restarted. We should continue to run')
            }
            salt.enforceState(saltMaster, 'ctl* or prx* or ctl*', ['linux', 'openssh', 'salt.minion', 'ntp', 'rsyslog'])

            // salt 'ctl*' state.sls keepalived
            // salt 'ctl*' state.sls haproxy
            salt.enforceState(saltMaster, 'ctl*', ['keepalived', 'haproxy'])
            // salt 'ctl*' service.restart rsyslog
            salt.runSaltProcessStep(saltMaster, 'ctl*', 'service.restart', ['rsyslog'], null, true)
            // salt "ctl*" state.sls memcached
            // salt "ctl*" state.sls keystone.server
            try {
                try {
                    salt.enforceState(saltMaster, 'ctl*', ['memcached', 'keystone.server'])
                    salt.runSaltProcessStep(saltMaster, 'ctl*', 'service.restart', ['apache2'], null, true)
                } catch (Exception e) {
                    common.warningMsg('Restarting Apache2 and enforcing keystone.server state again')
                    salt.runSaltProcessStep(saltMaster, 'ctl*', 'service.restart', ['apache2'], null, true)
                    salt.enforceState(saltMaster, 'ctl*', 'keystone.server')
                }
                // salt 'ctl01*' state.sls keystone.client
                try {
                    salt.enforceState(saltMaster, 'I@keystone:client and ctl*', 'keystone.client')
                } catch (Exception e) {
                    common.warningMsg('running keystone.client state again')
                    salt.enforceState(saltMaster, 'I@keystone:client and ctl*', 'keystone.client')
                }
                try {
                    salt.enforceState(saltMaster, 'ctl*', 'glance')
                } catch (Exception e) {
                    common.warningMsg('running glance state again')
                    salt.enforceState(saltMaster, 'ctl*', 'glance')
                }                // salt 'ctl*' state.sls glusterfs.client
                salt.enforceState(saltMaster, 'ctl*', 'glusterfs.client')
                // salt 'ctl*' state.sls keystone.server
                salt.enforceState(saltMaster, 'ctl*', 'keystone.server')
                // salt 'ctl*' state.sls nova
                try {
                    salt.enforceState(saltMaster, 'ctl*', 'nova')
                } catch (Exception e) {
                    common.warningMsg('running nova state again')
                    salt.enforceState(saltMaster, 'ctl*', 'nova')
                }
                // salt 'ctl*' state.sls cinder
                try {
                    salt.enforceState(saltMaster, 'ctl*', 'cinder')
                } catch (Exception e) {
                    common.warningMsg('running cinder state again')
                    salt.enforceState(saltMaster, 'ctl*', 'cinder')
                }
                try {
                    salt.enforceState(saltMaster, 'ctl*', 'neutron')
                } catch (Exception e) {
                    common.warningMsg('running neutron state again')
                    salt.enforceState(saltMaster, 'ctl*', 'neutron')
                }
                // salt 'ctl*' state.sls heat
                try {
                    salt.enforceState(saltMaster, 'ctl*', 'heat')
                } catch (Exception e) {
                    common.warningMsg('running heat state again')
                    salt.enforceState(saltMaster, 'ctl*', 'heat')
                }

            } catch (Exception e) {
                errorOccured = true
                common.warningMsg('Some states that require syncdb failed. Restoring production databases')

                // database restore section
                try {
                    salt.runSaltProcessStep(saltMaster, 'I@galera:slave', 'service.stop', ['mysql'], null, true)
                } catch (Exception er) {
                    common.warningMsg('Mysql service already stopped')
                }
                try {
                    salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'service.stop', ['mysql'], null, true)
                } catch (Exception er) {
                    common.warningMsg('Mysql service already stopped')
                }
                try {
                    salt.cmdRun(saltMaster, 'I@galera:slave', "rm /var/lib/mysql/ib_logfile*")
                } catch (Exception er) {
                    common.warningMsg('Files are not present')
                }
                try {
                    salt.cmdRun(saltMaster, 'I@galera:master', "mkdir /root/mysql/mysql.bak")
                } catch (Exception er) {
                    common.warningMsg('Directory already exists')
                }
                try {
                    salt.cmdRun(saltMaster, 'I@galera:master', "rm -rf /root/mysql/mysql.bak/*")
                } catch (Exception er) {
                    common.warningMsg('Directory already empty')
                }
                try {
                    salt.cmdRun(saltMaster, 'I@galera:master', "mv /var/lib/mysql/* /root/mysql/mysql.bak")
                } catch (Exception er) {
                    common.warningMsg('Files were already moved')
                }
                try {
                    salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'file.remove', ["/var/lib/mysql/.galera_bootstrap"], null, true)
                } catch (Exception er) {
                    common.warningMsg('File is not present')
                }
                salt.cmdRun(saltMaster, 'I@galera:master', "sed -i '/gcomm/c\\wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")
                _pillar = salt.getPillar(saltMaster, "I@galera:master", 'xtrabackup:client:backup_dir')
                backup_dir = _pillar['return'][0].values()[0]
                if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/mysql/xtrabackup' }
                print(backup_dir)
                salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'file.remove', ["${backup_dir}/dbrestored"], null, true)
                salt.cmdRun(saltMaster, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
                salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'service.start', ['mysql'], null, true)

                // wait until mysql service on galera master is up
                salt.commandStatus(saltMaster, 'I@galera:master', 'service mysql status', 'running')

                salt.runSaltProcessStep(saltMaster, 'I@galera:slave', 'service.start', ['mysql'], null, true)
                //

                common.errorMsg("Stage Real control upgrade failed")
            }
            if(!errorOccured){

                ceph = null

                try {
                    ceph = salt.cmdRun(saltMaster, 'ctl*', "salt-call grains.item roles | grep ceph.client")

                } catch (Exception er) {
                    common.infoMsg("Ceph is not used")
                }

                if(ceph != null) {
                    try {
                        salt.enforceState(saltMaster, 'ctl*', 'ceph.client')
                    } catch (Exception er) {
                        common.warningMsg("Ceph client state on controllers failed. Please fix it manually")
                    }
                }

                // salt 'cmp*' cmd.run 'service nova-compute restart'
                salt.runSaltProcessStep(saltMaster, 'cmp*', 'service.restart', ['nova-compute'], null, true)
                salt.runSaltProcessStep(saltMaster, 'ctl*', 'service.restart', ['nova-conductor'], null, true)
                salt.runSaltProcessStep(saltMaster, 'ctl*', 'service.restart', ['nova-scheduler'], null, true)


                // salt 'prx*' state.sls linux,openssh,salt.minion,ntp,rsyslog
                // salt 'ctl*' state.sls keepalived
                // salt 'prx*' state.sls keepalived
                salt.enforceState(saltMaster, 'prx*', 'keepalived')
                // salt 'prx*' state.sls horizon
                salt.enforceState(saltMaster, 'prx*', 'horizon')
                // salt 'prx*' state.sls nginx
                salt.enforceState(saltMaster, 'prx*', 'nginx')
                // salt "prx*" state.sls memcached
                salt.enforceState(saltMaster, 'prx*', 'memcached')

                try {
                    salt.enforceHighstate(saltMaster, 'ctl*')
                } catch (Exception er) {
                    common.errorMsg("Highstate was executed on controller nodes but something failed. Please check it and fix it accordingly.")
                }

                try {
                    salt.enforceHighstate(saltMaster, 'prx*')
                } catch (Exception er) {
                    common.errorMsg("Highstate was executed on proxy nodes but something failed. Please check it and fix it accordingly.")
                }

                salt.cmdRun(saltMaster, 'ctl01*', '. /root/keystonercv3; openstack service list; openstack image list; openstack flavor list; openstack compute service list; openstack server list; openstack network list; openstack volume list; openstack orchestration service list')
            }
        }

        if (STAGE_REAL_UPGRADE.toBoolean() == true && STAGE_ROLLBACK_UPGRADE.toBoolean() == true) {
            stage('Ask for manual confirmation') {
                input message: "Please verify if the control upgrade was successful. If it did not succeed, in the worst scenario, you can click YES to continue with control-upgrade-rollback. Do you want to continue with the rollback?"
            }
        }
    }

    if (STAGE_ROLLBACK_UPGRADE.toBoolean() == true) {
        stage('Rollback upgrade') {

            stage('Ask for manual confirmation') {
                input message: "Do you really want to continue with the rollback?"
            }

            _pillar = salt.getGrain(saltMaster, 'I@salt:master', 'domain')
            domain = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(domain)

            _pillar = salt.getGrain(saltMaster, 'I@salt:control', 'id')
            kvm01 = _pillar['return'][0].values()[0].values()[0]
            print(_pillar)
            print(kvm01)

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:ctl01:provider')
            def ctl01NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:ctl02:provider')
            def ctl02NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:ctl03:provider')
            def ctl03NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:prx01:provider')
            def prx01NodeProvider = _pillar['return'][0].values()[0]

            _pillar = salt.getPillar(saltMaster, "${kvm01}", 'salt:control:cluster:internal:node:prx02:provider')
            def prx02NodeProvider = _pillar['return'][0].values()[0]

            salt.runSaltProcessStep(saltMaster, "${prx01NodeProvider}", 'virt.destroy', ["prx01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${prx02NodeProvider}", 'virt.destroy', ["prx02.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl01NodeProvider}", 'virt.destroy', ["ctl01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl02NodeProvider}", 'virt.destroy', ["ctl02.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl03NodeProvider}", 'virt.destroy', ["ctl03.${domain}"], null, true)

            salt.runSaltProcessStep(saltMaster, "${prx01NodeProvider}", 'file.copy', ["/root/prx01.${domain}.qcow2.bak", "/var/lib/libvirt/images/prx01.${domain}/system.qcow2"], null, true)
            salt.runSaltProcessStep(saltMaster, "${prx02NodeProvider}", 'file.copy', ["/root/prx02.${domain}.qcow2.bak", "/var/lib/libvirt/images/prx02.${domain}/system.qcow2"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl01NodeProvider}", 'file.copy', ["/root/ctl01.${domain}.qcow2.bak", "/var/lib/libvirt/images/ctl01.${domain}/system.qcow2"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl02NodeProvider}", 'file.copy', ["/root/ctl02.${domain}.qcow2.bak", "/var/lib/libvirt/images/ctl02.${domain}/system.qcow2"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl03NodeProvider}", 'file.copy', ["/root/ctl03.${domain}.qcow2.bak", "/var/lib/libvirt/images/ctl03.${domain}/system.qcow2"], null, true)

            try {
                salt.cmdRun(saltMaster, 'I@salt:master', "salt-key -d ctl01.${domain},ctl02.${domain},ctl03.${domain},prx01.${domain},prx02.${domain} -y")
            } catch (Exception e) {
                common.warningMsg('does not match any accepted, unaccepted or rejected keys. They were probably already removed. We should continue to run')
            }

            // database restore section
            try {
                salt.runSaltProcessStep(saltMaster, 'I@galera:slave', 'service.stop', ['mysql'], null, true)
            } catch (Exception e) {
                common.warningMsg('Mysql service already stopped')
            }
            try {
                salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'service.stop', ['mysql'], null, true)
            } catch (Exception e) {
                common.warningMsg('Mysql service already stopped')
            }
            try {
                salt.cmdRun(saltMaster, 'I@galera:slave', "rm /var/lib/mysql/ib_logfile*")
            } catch (Exception e) {
                common.warningMsg('Files are not present')
            }
            try {
                salt.cmdRun(saltMaster, 'I@galera:master', "rm -rf /var/lib/mysql/*")
            } catch (Exception e) {
                common.warningMsg('Directory already empty')
            }
            try {
                salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'file.remove', ["/var/lib/mysql/.galera_bootstrap"], null, true)
            } catch (Exception e) {
                common.warningMsg('File is not present')
            }
            salt.cmdRun(saltMaster, 'I@galera:master', "sed -i '/gcomm/c\\wsrep_cluster_address=\"gcomm://\"' /etc/mysql/my.cnf")
            _pillar = salt.getPillar(saltMaster, "I@galera:master", 'xtrabackup:client:backup_dir')
            backup_dir = _pillar['return'][0].values()[0]
            if(backup_dir == null || backup_dir.isEmpty()) { backup_dir='/var/backups/mysql/xtrabackup' }
            print(backup_dir)
            salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'file.remove', ["${backup_dir}/dbrestored"], null, true)
            salt.cmdRun(saltMaster, 'I@xtrabackup:client', "su root -c 'salt-call state.sls xtrabackup'")
            salt.runSaltProcessStep(saltMaster, 'I@galera:master', 'service.start', ['mysql'], null, true)

            // wait until mysql service on galera master is up
            salt.commandStatus(saltMaster, 'I@galera:master', 'service mysql status', 'running')

            salt.runSaltProcessStep(saltMaster, 'I@galera:slave', 'service.start', ['mysql'], null, true)
            //

            salt.runSaltProcessStep(saltMaster, "${prx01NodeProvider}", 'virt.start', ["prx01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${prx02NodeProvider}", 'virt.start', ["prx02.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl01NodeProvider}", 'virt.start', ["ctl01.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl02NodeProvider}", 'virt.start', ["ctl02.${domain}"], null, true)
            salt.runSaltProcessStep(saltMaster, "${ctl03NodeProvider}", 'virt.start', ["ctl03.${domain}"], null, true)

            // salt 'cmp*' cmd.run 'service nova-compute restart'
            salt.runSaltProcessStep(saltMaster, 'cmp*', 'service.restart', ['nova-compute'], null, true)

            // wait until ctl and prx nodes are registered in salt-key
            salt.minionPresent(saltMaster, 'I@salt:master', 'ctl01')
            salt.minionPresent(saltMaster, 'I@salt:master', 'ctl02')
            salt.minionPresent(saltMaster, 'I@salt:master', 'ctl03')
            salt.minionPresent(saltMaster, 'I@salt:master', 'prx01')
            salt.minionPresent(saltMaster, 'I@salt:master', 'prx02')

            salt.runSaltProcessStep(saltMaster, 'ctl*', 'service.restart', ['nova-conductor'], null, true)
            salt.runSaltProcessStep(saltMaster, 'ctl*', 'service.restart', ['nova-scheduler'], null, true)

            salt.cmdRun(saltMaster, 'ctl01*', '. /root/keystonerc; nova service-list; glance image-list; nova flavor-list; nova hypervisor-list; nova list; neutron net-list; cinder list; heat service-list')
        }
    }
}
