/*
* Dateiname: OOoDatasource.java
* Projekt  : WollMux
* Funktion : Stellt eine OOo-Datenquelle als WollMux-Datenquelle zur Verf�gung
* 
* Copyright: Landeshauptstadt M�nchen
*
* �nderungshistorie:
* Datum      | Wer | �nderungsgrund
* -------------------------------------------------------------------
* 19.12.2006 | BNK | Erstellung
* 21.12.2006 | BNK | Fertig+Test
* -------------------------------------------------------------------
*
* @author Matthias Benkmann (D-III-ITD 5.1)
* @version 1.0
* 
*/
package de.muenchen.allg.itd51.wollmux.db;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import com.sun.star.container.XNameAccess;
import com.sun.star.sdbc.XColumnLocate;
import com.sun.star.sdbc.XConnection;
import com.sun.star.sdbc.XDataSource;
import com.sun.star.sdbc.XResultSet;
import com.sun.star.sdbc.XRow;
import com.sun.star.sdbc.XStatement;
import com.sun.star.sdbcx.XColumnsSupplier;
import com.sun.star.sdbcx.XKeysSupplier;

import de.muenchen.allg.afid.UNO;
import de.muenchen.allg.itd51.parser.ConfigThingy;
import de.muenchen.allg.itd51.parser.NodeNotFoundException;
import de.muenchen.allg.itd51.wollmux.ConfigurationErrorException;
import de.muenchen.allg.itd51.wollmux.Logger;
import de.muenchen.allg.itd51.wollmux.TimeoutException;

/**
 * Stellt eine OOo-Datenquelle als WollMux-Datenquelle zur Verf�gung.
 *
 * @author Matthias Benkmann (D-III-ITD 5.1)
 */
public class OOoDatasource implements Datasource
{
  /**
   * Maximale Zeit in Sekunden, die die Datenquelle f�r die Verbindungsaufnahme mit der
   * Datenbank brauchen darf.
   */
  private static final int LOGIN_TIMEOUT = 5;
  
  /**
   * Der Name dieser Datenquelle.
   */
  private String datasourceName;
  
  /**
   * Der Name der OpenOffice-Datenquelle.
   */
  private String oooDatasourceName;
  
  /**
   * Der Name der Tabelle in der OpenOffice-Datenquelle.
   */
  private String oooTableName;
  
  /**
   * Das Schema dieser Datenquelle.
   */
  private Set schema;
  
  /**
   * Die Namen der Spalten, die den Prim�rschl�ssel bilden.
   */
  private String[] keyColumns;
  
  /**
   * Erzeugt eine neue OOoDatasource.
   * 
   * @param nameToDatasource
   *          enth�lt alle bis zum Zeitpunkt der Definition dieser
   *          OOoDatasource bereits vollst�ndig instanziierten Datenquellen
   *          (zur Zeit nicht verwendet).
   * @param sourceDesc
   *          der "Datenquelle"-Knoten, der die Beschreibung dieser
   *          OOoDatasource enth�lt.
   * @param context
   *          der Kontext relativ zu dem URLs aufgel�st werden sollen (zur Zeit
   *          nicht verwendet).
   * @throws ConfigurationErrorException
   *           falls in der Definition in sourceDesc ein Fehler ist.
   *           Falls sourceDesc keinen Schema-Unterabschnitt aufweist, wird versucht,
   *           das Schema von der Datenquelle selbst zu bekommen. Tritt dabei ein
   *           Fehler auf wird ebenfalls diese Exception geworfen.
   *           *Keine* Exception wird geworfen, wenn die Spalten des Schema-Abschnitts nicht in
   *           der realen Datenbank vorhanden sind. In diesem Fall werden die entsprechenden Spalten
   *           als leer behandelt. 
   * TESTED */
  public OOoDatasource(Map nameToDatasource, ConfigThingy sourceDesc,
      URL context) throws ConfigurationErrorException
  {
    try
    {
      datasourceName = sourceDesc.get("NAME").toString();
    }
    catch (NodeNotFoundException x)
    { throw new ConfigurationErrorException("NAME der Datenquelle fehlt"); }
    
    try
    {
      oooDatasourceName = sourceDesc.get("SOURCE").toString();
    }
    catch (NodeNotFoundException x)
    { throw new ConfigurationErrorException("Datenquelle \""+datasourceName+"\": Name der OOo-Datenquelle muss als SOURCE angegeben werden"); }
    
    try
    {
      oooTableName = sourceDesc.get("TABLE").toString();
    }
    catch (NodeNotFoundException x)
    { throw new ConfigurationErrorException("Datenquelle \""+datasourceName+"\": Name der Tabelle/Sicht innerhalb der OOo-Datenquelle muss als TABLE angegeben werden"); }
    
    
    schema = new HashSet();
    ConfigThingy schemaConf = sourceDesc.query("Schema");
    if (schemaConf.count() != 0)
    {
      Iterator iter = ((ConfigThingy)schemaConf.iterator().next()).iterator();
      while (iter.hasNext())
      {
        schema.add(iter.next().toString());
      }
      if (schema.size() == 0) throw new ConfigurationErrorException("Datenquelle \""+datasourceName+"\": Schema-Abschnitt ist leer");
      ConfigThingy schluesselConf = sourceDesc.query("Schluessel");
      if (schluesselConf.count() == 0)
        throw new ConfigurationErrorException("Datenquelle \""+datasourceName+"\": Schluessel-Abschnitt fehlt");

      parseKey(schluesselConf); //Test ob kein Schluessel vorhanden siehe weiter unten
    }
    else
    {
      Logger.debug("Schema der Datenquelle "+datasourceName+" nicht angegeben. Versuche, es von der Datenquelle zu erfragen.");
      try{
        XDataSource ds = UNO.XDataSource(UNO.dbContext.getRegisteredObject(oooDatasourceName));
        ds.setLoginTimeout(LOGIN_TIMEOUT);
        XConnection conn = ds.getConnection("","");
        
        /*
         * Laut IDL-Doku zu "View" m�ssen hier auch die Views enthalten sein.
         */
        XNameAccess tables = UNO.XTablesSupplier(conn).getTables();
        Object table = tables.getByName(oooTableName);
        XNameAccess columns = UNO.XColumnsSupplier(table).getColumns();
        String[] colNames = columns.getElementNames();
        for (int i = 0; i < colNames.length; ++i)
          schema.add(colNames[i]);
        
        if (schema.size() == 0) throw new ConfigurationErrorException("Datenquelle \""+datasourceName+"\": Tabelle \""+oooTableName+"\" hat keine Spalten");
        
        ConfigThingy schluesselConf = sourceDesc.query("Schluessel");
        if (schluesselConf.count() != 0)
          parseKey(schluesselConf); //Test ob kein Schluessel vorhanden siehe weiter unten
        else
        {  //Schl�ssel von Datenbank abfragen.
          XKeysSupplier keysSupp = UNO.XKeysSupplier(table);
          XColumnsSupplier colSupp = UNO.XColumnsSupplier(keysSupp.getKeys().getByIndex(0));
          columns = colSupp.getColumns();
          colNames = columns.getElementNames();
          keyColumns = new String[colNames.length];
          System.arraycopy(colNames, 0, keyColumns, 0, keyColumns.length);
           //Test ob kein Schluessel vorhanden siehe weiter unten
        }
      }
      catch(Exception x)
      {
        throw new ConfigurationErrorException("Konnte Schema der OOo-Datenquelle \""+oooDatasourceName+"\" nicht auslesen.", x);
      }
      
      if (keyColumns.length == 0) throw new ConfigurationErrorException("Datenquelle \""+datasourceName+"\": Keine Schluessel-Spalten definiert");
    }
  }
  
  /**
   * Parst das erste Kind von conf (das existieren und ein Schluessel-Knoten sein muss) und
   * setzt {@link #keyColumns} entsprechend.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   * @throws ConfigurationErrorException falls eine Schluessel-Spalte nicht im {@link #schema} ist.
   *         Es wird *keine* Exception geworfen, wenn der Schluessel-Abschnitt leer ist.
   * TESTED*/
  private void parseKey(ConfigThingy conf) throws ConfigurationErrorException
  {
    conf = (ConfigThingy)conf.iterator().next();
    Iterator iter = conf.iterator();
    ArrayList columns = new ArrayList();
    while (iter.hasNext())
    {
      String column = iter.next().toString();
      if (!schema.contains(column))
        throw new ConfigurationErrorException("Schluessel-Spalte \""+column+"\" nicht im Schema enthalten");
      if (columns.contains(column))
        throw new ConfigurationErrorException("Schluessel-Spalte \""+column+"\" ist im Schluessel-Abschnitt doppelt angegeben");
      
      columns.add(column);
    }
    keyColumns = new String[columns.size()];
    keyColumns = (String[])columns.toArray(keyColumns);
  }
  
  public Set getSchema()
  {
    return schema;
  }

  public QueryResults getDatasetsByKey(Collection keys, long timeout) throws TimeoutException
  { //TESTED
    long endTime = System.currentTimeMillis() + timeout;
    StringBuilder buffy = new StringBuilder("SELECT * FROM "+sqlIdentifier(oooTableName)+" WHERE ");
    
    Iterator iter = keys.iterator();
    boolean first = true;
    while (iter.hasNext())
    {
      if (!first) buffy.append(" OR "); 
      first = false;
      String key = (String)iter.next();
      String[] parts = key.split("#",-1);
      buffy.append('(');
      for (int i = 1; i < parts.length; i+=2)
      {
        if (i > 1) buffy.append(" AND ");
        buffy.append(sqlIdentifier(decode(parts[i-1])));
        buffy.append('=');
        buffy.append(sqlLiteral(decode(parts[i])));
      }
      buffy.append(')');
    }
    
    buffy.append(';');
    
    timeout = endTime - System.currentTimeMillis();
    if (timeout < 1) timeout = 1;
    return sqlQuery(buffy.toString(), timeout, true);
  }

  public QueryResults find(List query, long timeout) throws TimeoutException
  { //TESTED 
    if (query.isEmpty()) return new QueryResultsList(new Vector(0));
    
    StringBuilder buffy = new StringBuilder("SELECT * FROM "+sqlIdentifier(oooTableName)+" WHERE ");
    
    Iterator iter = query.iterator();
    boolean first = true;
    while (iter.hasNext())
    {
      QueryPart part = (QueryPart)iter.next();
      if (!first) buffy.append(" AND ");
      first = false;
      buffy.append('(');
      buffy.append("lower(");
      buffy.append(sqlIdentifier(part.getColumnName()));
      buffy.append(')');
      buffy.append(" LIKE ");
      buffy.append("lower(");
      buffy.append(sqlLiteral(sqlSearchPattern(part.getSearchString())));
      buffy.append(") ESCAPE '\\'");

      buffy.append(')');
    }
    
    buffy.append(';');
    return sqlQuery(buffy.toString(), timeout, true);
  }

  
  public QueryResults getContents(long timeout) throws TimeoutException
  {
    String command = "SELECT * FROM "+sqlIdentifier(oooTableName)+";";
    return sqlQuery(command, timeout, false);
  }

  /**
   * Setzt die SQL-Anfrage query an die Datenbank ab und liefert die Resultate.
   * @param timeout maximale Zeit in Millisekunden, die die Anfrage dauern darf.
   * @param throwOnTimeout falls true wird im Falle des �berschreitens des Timeouts eine
   *        TimeoutException geworfen, ansonsten wird die unvollst�ndige Ergebnisliste 
   *        zur�ckgeliefert.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   * TESTED
   */
  private QueryResults sqlQuery(String query, long timeout, boolean throwOnTimeout) throws TimeoutException
  {
    Logger.debug("sqlQuery(\""+query+"\", "+timeout+", "+throwOnTimeout+")");
    long endTime = System.currentTimeMillis() + timeout;
    
    Vector datasets = new Vector();
    
    if (System.currentTimeMillis() > endTime)
    {
      if (throwOnTimeout)
        throw new TimeoutException("Konnte Anfrage nicht innerhalb der vorgegebenen Zeit vollenden");
      else
        return new QueryResultsList(datasets);
    }
    
    XConnection conn;
    try{
      XDataSource ds = UNO.XDataSource(UNO.dbContext.getRegisteredObject(oooDatasourceName));
      long lgto = timeout / 1000;
      if (lgto < 1) lgto = 1;
      ds.setLoginTimeout((int)lgto);
      conn = ds.getConnection("","");
    } 
    catch(Exception x)
    {
      throw new TimeoutException("Kann keine Verbindung zur Datenquelle \""+oooDatasourceName+"\" herstellen");
    }
    
    try{
      XStatement statement = conn.createStatement();
      XResultSet results = statement.executeQuery(query);
      Map mapColumnNameToIndex = getColumnMapping(results);
      XRow row = UNO.XRow(results);
      
      while (results != null && results.next())
      {
        Map data = new HashMap();
        Iterator iter = mapColumnNameToIndex.entrySet().iterator();
        while (iter.hasNext())
        {
          Map.Entry entry = (Map.Entry)iter.next();
          String column = (String)entry.getKey();
          int idx = ((Number)entry.getValue()).intValue();
          String value = null;
          if (idx > 0) value = row.getString(idx);
          data.put(column, value);
        }
        datasets.add(new OOoDataset(data));
        if (System.currentTimeMillis() > endTime)
        {
          if (throwOnTimeout)
            throw new TimeoutException("Konnte Anfrage nicht innerhalb der vorgegebenen Zeit vollenden");
          else
            break;
        }
      }
    } catch(Exception x)
    {
      throw new TimeoutException("Fehler beim Absetzen der Anfrage",x);
    }
    return new QueryResultsList(datasets);
  }
  
  /**
   * Liefert eine Abbildung der Spaltennamen aus {@link #schema} auf Integer-Indizes, die die 
   * Spaltennummern f�r XRow(results)::getString() sind. Falls eine Spalte nicht existiert, ist
   * ihr index <= 0.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   * TESTED
   */
  private Map getColumnMapping(XResultSet results)
  {
    Map mapColumnNameToIndex = new HashMap();
    XColumnLocate loc = UNO.XColumnLocate(results);
    Iterator iter = getSchema().iterator();
    while (iter.hasNext())
    {
      String column = (String)iter.next();
      int idx = -1;
      try{
        idx = loc.findColumn(column);
      } catch(Exception x){}
      mapColumnNameToIndex.put(column, new Integer(idx));
    }
    return mapColumnNameToIndex;
  }
  
  /**
   * Liefert str zur�ck, als String-Literal vorbereitet f�r das Einf�gen in SQL-Statements. 
   * @param str beginnt und endet immer mit einem Apostroph.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  private static String sqlLiteral(String str)
  {
    return "'"+str.replaceAll("'","''")+"'";
  }
  
  /**
   * Liefert str zur�ck, als Identifier-Name vorbereitet f�r das Einf�gen in SQL-Statements. 
   * @param str beginnt und endet immer mit einem Doublequote.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  private static String sqlIdentifier(String str)
  {
    return "\""+str.replaceAll("\"","\"\"")+"\"";
  }

  /**
   * Ersetzt das * Wildcard so dass ein SQL-Suchmuster entsteht.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  private static String sqlSearchPattern(String str)
  {
    return str.replaceAll("\\\\","\\\\\\\\").replaceAll("_","\\\\_").replaceAll("%","\\\\%").replaceAll("\\*","%"); 
  }
  
  public String getName()
  {
    return datasourceName;
  }
  
  private class OOoDataset implements Dataset
  {
    private Map data;
    private String key;
    
    public OOoDataset(Map data)
    {
      this.data = data;
      initKey(keyColumns);
    }
    
    /**
     * Setzt aus den Werten der Schl�sselspalten den Schl�ssel zusammen.
     * @param keyCols die Namen der Schl�sselspalten
     * @author Matthias Benkmann (D-III-ITD 5.1)
     */
    private void initKey(String[] keyCols)
    { //TESTED
      StringBuilder buffy = new StringBuilder();
      for (int i = 0; i < keyCols.length; ++i)
      {
        String str = (String)data.get(keyCols[i]);
        if (str != null) 
        {
          buffy.append(encode(keyCols[i]));
          buffy.append('#');
          buffy.append(encode(str));
          buffy.append('#');
        }
      }
      
      key = buffy.toString();
    }
    
    public String get(String columnName) throws ColumnNotFoundException
    {
      if (!schema.contains(columnName)) throw new ColumnNotFoundException("Spalte "+columnName+" existiert nicht!");
      return (String)data.get(columnName);
    }

    public String getKey()
    {
      return key;
    }
    
  }

  private static String encode(String str)
  {
    return str.replaceAll("%", "%%").replace("#","%r");
  }
  
  private static String decode(String str)
  { //TESTED
    StringBuilder buffy = new StringBuilder(str);
    int i = 0;
    while (0 <= (i = buffy.indexOf("%", i)))
    {
      ++i;
      if (buffy.charAt(i) == 'r')
        buffy.replace(i-1, i+1, "#");
      else
        buffy.deleteCharAt(i);
    }
    return buffy.toString();
  }

  
  private static void printQueryResults(Set schema, QueryResults res, Vector keys) throws ColumnNotFoundException
  {
    keys.clear();
    Iterator iter;
    iter = res.iterator();
    while (iter.hasNext())
    {
      Dataset data = (Dataset)iter.next();
      keys.add(data.getKey());
      Iterator colIter = schema.iterator();
      while (colIter.hasNext())
      {
        String col = (String)colIter.next();
        String val = data.get(col); 
        if (val == null) 
          val = "unbelegt";
        else
          val = "\"" + val + "\"";
        
        System.out.print(col+"="+val+" ");
      }
      System.out.println("  (Schl�ssel: \""+data.getKey()+"\")");
    }
  }

  /**
   * Gibt results aus.
   * 
   * @param query
   *          ein String der in die �berschrift der Ausgabe geschrieben wird,
   *          damit der Benutzer sieht, was er angezeigt bekommt.
   * @param schema
   *          bestimmt, welche Spalten angezeigt werden von den Datens�tzen aus
   *          results.
   * @param results
   *          die Ergebnisse der Anfrage.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public static void printResults(String query, Set schema, QueryResults results)
  {
    System.out.println("Results for query \"" + query + "\":");
    Iterator resIter = results.iterator();
    while (resIter.hasNext())
    {
      Dataset result = (Dataset) resIter.next();

      Iterator spiter = schema.iterator();
      while (spiter.hasNext())
      {
        String spalte = (String) spiter.next();
        String wert = "Spalte " + spalte + " nicht gefunden!";
        try
        {
          wert = result.get(spalte);
          if (wert == null)
            wert = "unbelegt";
          else
            wert = "\"" + wert + "\"";
        }
        catch (ColumnNotFoundException x)
        {
        }
        ;
        System.out.print(spalte + "=" + wert + (spiter.hasNext() ? ", " : ""));
      }
      System.out.println();
    }
    System.out.println();
  }

  /**
   * 
   * @param spaltenName
   * @param suchString
   * @return
   * @throws TimeoutException
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  private QueryResults simpleFind(String spaltenName, String suchString)
      throws TimeoutException
  {
    List query = new Vector();
    query.add(new QueryPart(spaltenName, suchString));
    QueryResults find = find(query, 3000000);
    return find;
  }

  /**
   * 
   * @param spaltenName1
   * @param suchString1
   * @param spaltenName2
   * @param suchString2
   * @return
   * @throws TimeoutException
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  private QueryResults simpleFind(String spaltenName1, String suchString1,
      String spaltenName2, String suchString2) throws TimeoutException
  {
    List query = new Vector();
    query.add(new QueryPart(spaltenName1, suchString1));
    query.add(new QueryPart(spaltenName2, suchString2));
    QueryResults find = find(query, 3000000);
    return find;
  }


  
  
  /**
   * @author Matthias Benkmann (D-III-ITD 5.1)
   * 
   */
  public static void main(String[] args)
  {
    try{
      UNO.init();
      Logger.init(System.err, Logger.ALL);
      // Datenquelle(
      //   NAME "test"
      //   TYPE "ooo"
      //   SOURCE "datenbank"
      //   TABLE "UserAnsicht"
      //   Schema( "UserVorname" "UserNachname" "Beschreibung" )
      //   Schluessel("UserVorname" "UserNachname")
      //      # Wenn Schema()-Abschnitt angegeben ist, muss auch ein Schluessel-Abschnitt angegeben werden.
      // )
      ConfigThingy conf = new ConfigThingy("Datenquelle");
      conf.add("NAME").add("test");
      conf.add("TYPE").add("ooo");
      conf.add("SOURCE").add("datenbank");
      conf.add("TABLE").add("UserAnsicht");
      ConfigThingy keysConf = conf.add("Schluessel");
      keysConf.add("UserVorname");
      keysConf.add("UserNachname");
      
      OOoDatasource ds = new OOoDatasource(null, conf, null);
      System.out.println("Name: "+ds.getName());
      System.out.print("Schema: ");
      Set schema = ds.getSchema();
      Iterator iter = schema.iterator();
      while (iter.hasNext())
      {
        System.out.print("\""+iter.next()+"\" ");
      }
      System.out.println();
      System.out.print("Schl�sselspalten: ");
      for (int i = 0; i < ds.keyColumns.length; ++i)
        System.out.print("\""+ds.keyColumns[i]+"\" ");
      
      System.out.println("Datens�tze:");
      QueryResults res = ds.getContents(1000000);
      Vector keys = new Vector();
      printQueryResults(schema, res, keys);
      
      keys.remove(0);
      System.out.println("Rufe Datens�tze f�r folgende Schl�ssel ab:");
      iter = keys.iterator();
      while (iter.hasNext()) System.out.println("    "+iter.next());
      
      res = ds.getDatasetsByKey(keys, 10000000);
      printQueryResults(schema, res, keys);
      
      printResults("Beschreibung = *uTTer", schema, ds.simpleFind("Beschreibung", "*uTTer"));
      printResults("Beschreibung = *uTTer, UserVorname = Sina", schema, ds.simpleFind("Beschreibung", "*uTTer", "UserVorname", "Sina"));
      printResults("UserVorname = Hans, UserNachname = Mu%rster#rmann", schema, ds.simpleFind("UserVorname", "Hans", "UserNachname", "Mu%rster#rmann"));
      printResults("Beschreibung = \\Kind", schema, ds.simpleFind("Beschreibung", "\\Kind"));
      printResults("UserVorname = Hans, UserNachname = Mu%er#rmann (sic)  muss leer sein", schema, ds.simpleFind("UserVorname", "Hans", "UserNachname", "Mu%er#rmann"));
      printResults("UserVorname = *a*", schema, ds.simpleFind("UserVorname", "*a*"));
      
    }catch(Exception x)
    {
      x.printStackTrace();
    }
    System.exit(0);
  }

  
}