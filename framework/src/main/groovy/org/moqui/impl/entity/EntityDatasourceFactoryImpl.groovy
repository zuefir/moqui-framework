/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.moqui.context.TransactionInternal
import org.moqui.entity.*
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.naming.Context
import javax.naming.InitialContext
import javax.naming.NamingException
import javax.sql.DataSource

@CompileStatic
class EntityDatasourceFactoryImpl implements EntityDatasourceFactory {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDatasourceFactoryImpl.class)

    protected EntityFacadeImpl efi
    protected MNode datasourceNode
    protected String tenantId

    protected DataSource dataSource


    EntityDatasourceFactoryImpl() { }

    @Override
    EntityDatasourceFactory init(EntityFacade ef, MNode datasourceNode, String tenantId) {
        // local fields
        this.efi = (EntityFacadeImpl) ef
        this.datasourceNode = datasourceNode
        this.tenantId = tenantId

        // init the DataSource

        if (datasourceNode.hasChild("jndi-jdbc")) {
            EntityFacadeImpl.DatasourceInfo dsi = new EntityFacadeImpl.DatasourceInfo(efi, datasourceNode)

            try {
                InitialContext ic;
                if (dsi.serverJndi) {
                    Hashtable<String, Object> h = new Hashtable<String, Object>()
                    h.put(Context.INITIAL_CONTEXT_FACTORY, dsi.serverJndi.attribute("initial-context-factory"))
                    h.put(Context.PROVIDER_URL, dsi.serverJndi.attribute("context-provider-url"))
                    if (dsi.serverJndi.attribute("url-pkg-prefixes")) h.put(Context.URL_PKG_PREFIXES, dsi.serverJndi.attribute("url-pkg-prefixes"))
                    if (dsi.serverJndi.attribute("security-principal")) h.put(Context.SECURITY_PRINCIPAL, dsi.serverJndi.attribute("security-principal"))
                    if (dsi.serverJndi.attribute("security-credentials")) h.put(Context.SECURITY_CREDENTIALS, dsi.serverJndi.attribute("security-credentials"))
                    ic = new InitialContext(h)
                } else {
                    ic = new InitialContext()
                }

                this.dataSource = (DataSource) ic.lookup(dsi.jndiName)
                if (this.dataSource == null) {
                    logger.error("Could not find DataSource with name [${dsi.jndiName}] in JNDI server [${dsi.serverJndi ? dsi.serverJndi.attribute("context-provider-url") : "default"}] for datasource with group-name [${datasourceNode.attribute("group-name")}].")
                }
            } catch (NamingException ne) {
                logger.error("Error finding DataSource with name [${dsi.jndiName}] in JNDI server [${dsi.serverJndi ? dsi.serverJndi.attribute("context-provider-url") : "default"}] for datasource with group-name [${datasourceNode.attribute("group-name")}].", ne)
            }
        } else if (datasourceNode.hasChild("inline-jdbc")) {
            // special thing for embedded derby, just set an system property; for derby.log, etc
            if (datasourceNode.attribute("database-conf-name") == "derby" && !System.getProperty("derby.system.home")) {
                System.setProperty("derby.system.home", efi.ecfi.runtimePath + "/db/derby")
                logger.info("Set property derby.system.home to [${System.getProperty("derby.system.home")}]")
            }

            TransactionInternal ti = efi.getEcfi().getTransactionFacade().getTransactionInternal()
            this.dataSource = ti.getDataSource(efi, datasourceNode, tenantId)
        } else {
            throw new EntityException("Found datasource with no jdbc sub-element (in datasource with group-name [${datasourceNode.attribute("group-name")}])")
        }

        return this
    }

    @Override
    void destroy() {
        // NOTE: TransactionInternal DataSource will be destroyed when the TransactionFacade is destroyed
    }

    @Override
    boolean checkTableExists(String entityName) {
        EntityDefinition ed
        // just ignore EntityException on getEntityDefinition
        try { ed = efi.getEntityDefinition(entityName) } catch (EntityException e) { return false }
        // may happen if all entity names includes a DB view entity or other that doesn't really exist
        if (ed == null) return false
        return ed.tableExistsDbMetaOnly()
    }
    @Override
    void checkAndAddTable(String entityName) {
        EntityDefinition ed
        // just ignore EntityException on getEntityDefinition
        try { ed = efi.getEntityDefinition(entityName) } catch (EntityException e) { return }
        // may happen if all entity names includes a DB view entity or other that doesn't really exist
        if (ed == null) return
        efi.getEntityDbMeta().checkTableStartup(ed)
    }

    @Override
    EntityValue makeEntityValue(String entityName) {
        EntityDefinition entityDefinition = efi.getEntityDefinition(entityName)
        if (entityDefinition == null) throw new EntityException("Entity not found for name [${entityName}]")
        return new EntityValueImpl(entityDefinition, efi)
    }

    @Override
    EntityFind makeEntityFind(String entityName) { return new EntityFindImpl(efi, entityName) }

    @Override
    DataSource getDataSource() { return dataSource }
}
