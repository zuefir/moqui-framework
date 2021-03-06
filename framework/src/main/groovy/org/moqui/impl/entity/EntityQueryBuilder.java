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
package org.moqui.impl.entity;

import org.moqui.entity.EntityException;
import org.moqui.impl.entity.EntityJavaUtil.EntityConditionParameter;
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo;
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

public class EntityQueryBuilder {
    public EntityQueryBuilder(EntityDefinition entityDefinition, EntityFacadeImpl efi) {
        this.mainEntityDefinition = entityDefinition;
        this.efi = efi;
    }

    public EntityDefinition getMainEd() { return mainEntityDefinition; }

    /** @return StringBuilder meant to be appended to */
    public StringBuilder getSqlTopLevel() { return sqlTopLevelInternal; }

    /** returns List of EntityConditionParameter meant to be added to */
    public ArrayList<EntityConditionParameter> getParameters() { return parameters; }

    public Connection makeConnection() {
        connection = efi.getConnection(mainEntityDefinition.getEntityGroupName());
        return connection;
    }

    public void useConnection(Connection c) {
        connection = c;
        externalConnection = true;
    }

    protected static void handleSqlException(Exception e, String sql) {
        throw new EntityException("SQL Exception with statement:" + sql + "; " + e.toString(), e);
    }

    public PreparedStatement makePreparedStatement() {
        if (connection == null)
            throw new IllegalStateException("Cannot make PreparedStatement, no Connection in place");
        finalSql = sqlTopLevelInternal.toString();
        // if (this.mainEntityDefinition.getFullEntityName().contains("foo")) logger.warn("========= making crud PreparedStatement for SQL: ${sql}")
        if (isTraceEnabled) logger.trace("making crud PreparedStatement for SQL: " + finalSql);
        try {
            ps = connection.prepareStatement(finalSql);
        } catch (SQLException sqle) {
            handleSqlException(sqle, finalSql);
        }

        return ps;
    }

    public ResultSet executeQuery() throws EntityException {
        if (ps == null) throw new IllegalStateException("Cannot Execute Query, no PreparedStatement in place");
        boolean isError = false;
        boolean queryStats = efi.getQueryStats();
        long queryTime = 0;
        try {
            final long timeBefore = isTraceEnabled ? System.currentTimeMillis() : 0L;
            long beforeQuery = queryStats ? System.nanoTime() : 0;
            rs = ps.executeQuery();
            if (queryStats) queryTime = System.nanoTime() - beforeQuery;
            if (isTraceEnabled)
                logger.trace("Executed query with SQL [" + sqlTopLevelInternal.toString() + "] and parameters [" + String.valueOf(parameters) + "] in [" + String.valueOf((double) (System.currentTimeMillis() - timeBefore) / 1000) + "] seconds");
            return rs;
        } catch (SQLException sqle) {
            isError = true;
            throw new EntityException("Error in query for:" + sqlTopLevelInternal, sqle);
        } finally {
            if (queryStats) efi.saveQueryStats(mainEntityDefinition, finalSql, queryTime, isError);
        }

    }

    public int executeUpdate() throws EntityException {
        if (this.ps == null) throw new IllegalStateException("Cannot Execute Update, no PreparedStatement in place");
        boolean isError = false;
        boolean queryStats = efi.getQueryStats();
        long queryTime = 0;
        try {
            final long timeBefore = isTraceEnabled ? System.currentTimeMillis() : 0L;
            long beforeQuery = queryStats ? System.nanoTime() : 0;
            final int rows = ps.executeUpdate();
            if (queryStats) queryTime = System.nanoTime() - beforeQuery;

            if (isTraceEnabled)
                logger.trace("Executed update with SQL [" + sqlTopLevelInternal.toString() + "] and parameters [" + String.valueOf(parameters) + "] in [" + String.valueOf((double) (System.currentTimeMillis() - timeBefore) / 1000) + "] seconds changing [" + String.valueOf(rows) + "] rows");
            return rows;
        } catch (SQLException sqle) {
            isError = true;
            throw new EntityException("Error in update for:" + sqlTopLevelInternal, sqle);
        } finally {
            if (queryStats) efi.saveQueryStats(mainEntityDefinition, finalSql, queryTime, isError);
        }

    }

    /**
     * NOTE: this should be called in a finally clause to make sure things are closed
     */
    public void closeAll() throws SQLException {
        if (ps != null) {
            ps.close();
            ps = null;
        }

        if (rs != null) {
            rs.close();
            rs = null;
        }

        if (connection != null && !externalConnection) {
            connection.close();
            connection = null;
        }

    }

    /**
     * For when closing to be done in other places, like a EntityListIteratorImpl
     */
    public void releaseAll() {
        ps = null;
        rs = null;
        connection = null;
    }

    /* this is no longer used, causes problems, but might be useful at some point
    public static String sanitizeColumnName(String colName) {
        String interim = colName.replace(".", "_").replace("(", "_").replace(")", "_").replace("+", "_").replace(" ", "");
        while (interim.charAt(0) == '_') interim = interim.substring(1);
        while (interim.charAt(interim.length() - 1) == '_')
            interim = interim.substring(0, interim.length() - 1);
        while (interim.contains("__")) interim = interim.replace("__", "_");
        return interim;
    }
    */

    public void setPreparedStatementValue(int index, Object value, FieldInfo fieldInfo) throws EntityException {
        EntityJavaUtil.setPreparedStatementValue(this.ps, index, value, fieldInfo, this.mainEntityDefinition, this.efi);
    }

    public void setPreparedStatementValues() {
        // set all of the values from the SQL building in efb
        ArrayList<EntityConditionParameter> parms = parameters;
        int size = parms.size();
        for (int i = 0; i < size; i++) {
            EntityConditionParameter entityConditionParam = parms.get(i);
            entityConditionParam.setPreparedStatementValue(i + 1);
        }

    }

    public void makeSqlSelectFields(FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray) {
        boolean checkUserFields = mainEntityDefinition.allowUserField;
        int size = fieldInfoArray.length;
        if (size > 0) {
            if (fieldOptionsArray == null && mainEntityDefinition.getAllFieldInfoList().size() == size) {
                String allFieldsSelect = mainEntityDefinition.allFieldsSqlSelect;
                if (allFieldsSelect != null) {
                    sqlTopLevelInternal.append(mainEntityDefinition.allFieldsSqlSelect);
                    return;

                }

            }


            for (int i = 0; i < size; i++) {
                FieldInfo fi = fieldInfoArray[i];
                if (fi == null) break;
                if (fi.isUserField && !checkUserFields) continue;

                if (i > 0) sqlTopLevelInternal.append(", ");

                boolean appendCloseParen = false;
                if (fieldOptionsArray != null) {
                    FieldOrderOptions foo = fieldOptionsArray[i];
                    if (foo != null && foo.getCaseUpperLower() != null && fi.typeValue == 1) {
                        sqlTopLevelInternal.append(foo.getCaseUpperLower() ? "UPPER(" : "LOWER(");
                        appendCloseParen = true;
                    }

                }


                sqlTopLevelInternal.append(fi.getFullColumnName());

                if (appendCloseParen) sqlTopLevelInternal.append(")");
            }

        } else {
            sqlTopLevelInternal.append("*");
        }

    }

    public final EntityDefinition getMainEntityDefinition() {
        return mainEntityDefinition;
    }

    protected static final Logger logger = LoggerFactory.getLogger(EntityQueryBuilder.class);
    protected static final boolean isTraceEnabled = logger.isTraceEnabled();
    protected final EntityFacadeImpl efi;
    private final EntityDefinition mainEntityDefinition;
    protected static final int sqlInitSize = 500;
    protected StringBuilder sqlTopLevelInternal = new StringBuilder(sqlInitSize);
    protected String finalSql = (String) null;
    protected static final int parametersInitSize = 20;
    protected ArrayList<EntityConditionParameter> parameters = new ArrayList<EntityJavaUtil.EntityConditionParameter>(parametersInitSize);
    protected PreparedStatement ps = (PreparedStatement) null;
    protected ResultSet rs = (ResultSet) null;
    protected Connection connection = (Connection) null;
    protected boolean externalConnection = false;
}
