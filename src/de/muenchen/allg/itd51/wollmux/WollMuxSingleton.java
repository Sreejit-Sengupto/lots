/*
 * Dateiname: WollMuxSingleton.java
 * Projekt  : WollMux
 * Funktion : Singleton f�r zentrale WollMux-Methoden.
 * 
 * Copyright: Landeshauptstadt M�nchen
 *
 * �nderungshistorie:
 * Datum      | Wer | �nderungsgrund
 * -------------------------------------------------------------------
 * 14.10.2005 | LUT | Erstellung
 * 09.11.2005 | LUT | + Logfile wird jetzt erweitert (append-modus)
 *                    + verwenden des Konfigurationsparameters SENDER_SOURCE
 *                    + Erster Start des wollmux �ber wm_configured feststellen.
 * 05.12.2005 | BNK | line.separator statt \n     
 * 13.04.2006 | BNK | .wollmux/ Handling ausgegliedert in WollMuxFiles.
 * 20.04.2006 | LUT | �berarbeitung Code-Kommentare  
 * 20.04.2006 | BNK | DEFAULT_CONTEXT ausgegliedert nach WollMuxFiles
 * 21.04.2006 | LUT | + Robusteres Verhalten bei Fehlern w�hrend dem Einlesen 
 *                    von Konfigurationsdateien; 
 *                    + wohldefinierte Datenstrukturen
 *                    + Flag f�r EventProcessor: acceptEvents
 * 08.05.2006 | LUT | + isDebugMode()
 * 10.05.2006 | BNK | +parseGlobalFunctions()
 *                  | +parseFunctionDialogs()
 * 26.05.2006 | BNK | DJ initialisierung ausgelagert nacht WollMuxFiles
 * 06.06.2006 | LUT | + Abl�sung der Event-Klasse durch saubere Objektstruktur
 * -------------------------------------------------------------------
 *
 * @author Christoph Lutz (D-III-ITD 5.1)
 * @version 1.0
 * 
 */

package de.muenchen.allg.itd51.wollmux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import com.sun.star.awt.PosSize;
import com.sun.star.awt.Rectangle;
import com.sun.star.lang.XComponent;
import com.sun.star.uno.Exception;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.uno.XComponentContext;

import de.muenchen.allg.afid.UNO;
import de.muenchen.allg.afid.UnoService;
import de.muenchen.allg.itd51.parser.ConfigThingy;
import de.muenchen.allg.itd51.wollmux.db.DJDataset;
import de.muenchen.allg.itd51.wollmux.db.DJDatasetListElement;
import de.muenchen.allg.itd51.wollmux.db.DatasetNotFoundException;
import de.muenchen.allg.itd51.wollmux.db.DatasourceJoiner;
import de.muenchen.allg.itd51.wollmux.db.QueryResults;
import de.muenchen.allg.itd51.wollmux.dialog.DialogLibrary;
import de.muenchen.allg.itd51.wollmux.func.FunctionLibrary;

/**
 * Diese Klasse ist ein Singleton, welcher den WollMux initialisiert und alle
 * zentralen WollMux-Methoden zur Verf�gung stellt. Selbst der WollMux-Service
 * de.muenchen.allg.itd51.wollmux.comp.WollMux, der fr�her zentraler Anlaufpunkt
 * war, bedient sich gr��tenteils aus den zentralen Methoden des Singletons.
 */
public class WollMuxSingleton implements XPALProvider
{

  private static WollMuxSingleton singletonInstance = null;

  /**
   * Enth�lt die geparste Textfragmentliste, die in der wollmux.conf definiert
   * wurde.
   */
  private VisibleTextFragmentList textFragmentList;

  /**
   * Enth�lt die im Funktionen-Abschnitt der wollmux,conf definierten
   * Funktionen.
   */
  private FunctionLibrary globalFunctions;

  /**
   * Enth�lt die im Funktionsdialoge-Abschnitt der wollmux,conf definierten
   * Dialoge.
   */
  private DialogLibrary funcDialogs;

  /**
   * Enth�lt den default XComponentContext in dem der WollMux (bzw. das OOo)
   * l�uft.
   */
  private XComponentContext ctx;

  /**
   * Enth�lt alle registrierten SenderBox-Objekte.
   */
  private Vector registeredPALChangeListener;

  /**
   * Die HashMap enth�lt als Schl�ssel in HashableComponent eingepackte, aktuell
   * ge�ffnete OOo-Dokumenten und deren originale Fensterpositionen, die beim
   * Schlie�en der Dokumente wieder zur�ckgesetzt werden sollen. Der Mechanismus
   * wird derzeit verwendet, um die original Fensterposition eines Formulars
   * nach/beim Schlie�en wieder herzustellen.
   */
  private HashMap originalWindowPosSizes;

  /**
   * Die WollMux-Hauptklasse ist als singleton realisiert.
   */
  private WollMuxSingleton(XComponentContext ctx)
  {
    // Der XComponentContext wir hier gesichert und vom WollMuxSingleton mit
    // getXComponentContext zur�ckgeliefert.
    this.ctx = ctx;

    // Initialisiere die UNO-Klasse, so dass auch mit dieser Hilfsklasse
    // gearbeitet werden kann.
    try
    {
      UNO.init(ctx.getServiceManager());
    }
    catch (java.lang.Exception e)
    {
      Logger.error(e);
    }

    boolean successfulStartup = true;

    registeredPALChangeListener = new Vector();

    originalWindowPosSizes = new HashMap();

    WollMuxFiles.setupWollMuxDir();

    Logger.debug("StartupWollMux");
    Logger.debug("Build-Info: " + getBuildInfo());
    Logger.debug("wollmuxConfFile = "
                 + WollMuxFiles.getWollMuxConfFile().toString());
    Logger.debug("DEFAULT_CONTEXT \""
                 + WollMuxFiles.getDEFAULT_CONTEXT().toString()
                 + "\"");

    // VisibleTextFragmentList erzeugen
    textFragmentList = new VisibleTextFragmentList(WollMuxFiles
        .getWollmuxConf());

    // Versuchen, den DJ zu initialisieren und Flag setzen, falls nicht
    // erfolgreich.
    if (getDatasourceJoiner() == null) successfulStartup = false;

    /*
     * Globale Funktionsdialoge parsen. ACHTUNG! Muss vor parseGlobalFunctions()
     * erfolgen. Als context wird null �bergeben, weil globale Funktionen keinen
     * Kontext haben. TODO �berlegen, ob ein globaler Kontext doch Sinn machen
     * k�nnte. Dadurch k�nnten globale Funktionen globale Funktionsdialoge
     * darstellen, die global einheitliche Werte haben.
     */
    funcDialogs = WollMuxFiles.parseFunctionDialogs(WollMuxFiles
        .getWollmuxConf(), null, null);

    /*
     * Globale Funktionen parsen. ACHTUNG! Verwendet die Funktionsdialoge. Diese
     * m�ssen also vorher geparst sein. Als context wird null �bergeben, weil
     * globale Funktionen keinen Kontext haben.
     */
    globalFunctions = WollMuxFiles.parseFunctions(
        WollMuxFiles.getWollmuxConf(),
        getFunctionDialogs(),
        null,
        null);

    // Initialisiere EventProcessor
    getEventProcessor().setAcceptEvents(successfulStartup);

    // register global EventListener
    try
    {
      UnoService eventBroadcaster = UnoService.createWithContext(
          "com.sun.star.frame.GlobalEventBroadcaster",
          ctx);
      eventBroadcaster.xEventBroadcaster()
          .addEventListener(getEventProcessor());
    }
    catch (Exception e)
    {
      Logger.error(e);
    }
  }

  /**
   * Diese Methode liefert die Instanz des WollMux-Singletons. Ist der WollMux
   * noch nicht initialisiert, so liefert die Methode null!
   * 
   * @return Instanz des WollMuxSingletons oder null.
   */
  public static WollMuxSingleton getInstance()
  {
    return singletonInstance;
  }

  /**
   * Diese Methode initialisiert das WollMuxSingleton (nur dann, wenn es noch
   * nicht initialisiert wurde)
   */
  public static void initialize(XComponentContext ctx)
  {
    if (singletonInstance == null)
    {
      singletonInstance = new WollMuxSingleton(ctx);

      // Event ON_FIRST_INITIALIZE erzeugen:
      WollMuxEventHandler.handleInitialize();
    }
  }

  /**
   * Diese Methode liefert die erste Zeile aus der buildinfo-Datei der aktuellen
   * WollMux-Installation zur�ck. Der Build-Status wird w�hrend dem
   * Build-Prozess mit dem Kommando "svn info" auf das Projektverzeichnis
   * erstellt. Die Buildinfo-Datei buildinfo enth�lt die Paketnummer und die
   * svn-Revision und ist im WollMux.uno.pkg-Paket sowie in der
   * WollMux.uno.jar-Datei abgelegt.
   * 
   * Kann dieses File nicht gelesen werden, so wird eine entsprechende
   * Ersatzmeldung erzeugt (siehe Sourcecode).
   * 
   * @return Der Build-Status der aktuellen WollMux-Installation.
   */
  public String getBuildInfo()
  {
    try
    {
      URL url = WollMuxSingleton.class.getClassLoader()
          .getResource("buildinfo");
      if (url != null)
      {
        BufferedReader in = new BufferedReader(new InputStreamReader(url
            .openStream()));
        return in.readLine().toString();
      }
    }
    catch (java.lang.Exception x)
    {
    }
    return "Die Datei buildinfo konnte nicht gelesen werden.";
  }

  /**
   * @return Returns the textFragmentList.
   */
  public VisibleTextFragmentList getTextFragmentList()
  {
    return textFragmentList;
  }

  /**
   * @return Returns the xComponentContext.
   */
  public XComponentContext getXComponentContext()
  {
    return ctx;
  }

  /**
   * Diese Methode liefert eine Instanz auf den aktuellen DatasourceJoiner
   * zur�ck.
   * 
   * @return Returns the datasourceJoiner.
   */
  public DatasourceJoiner getDatasourceJoiner()
  {
    return WollMuxFiles.getDatasourceJoiner();
  }

  /**
   * Diese Methode registriert einen XPALChangeEventListener, der updates
   * empf�ngt wenn sich die PAL �ndert. Die Methode ignoriert alle
   * XPALChangeEventListenener-Instanzen, die bereits registriert wurden.
   * Mehrfachregistrierung der selben Instanz ist also nicht m�glich.
   * 
   * Achtung: Die Methode darf nicht direkt von einem UNO-Service aufgerufen
   * werden, sondern jeder Aufruf muss �ber den EventHandler laufen. Deswegen
   * exportiert WollMuxSingleton auch nicht das
   * XPALChangedBroadcaster-Interface.
   */
  public void addPALChangeEventListener(XPALChangeEventListener listener)
  {
    Logger.debug2("WollMuxSingleton::addPALChangeEventListener()");

    if (listener == null) return;

    Iterator i = registeredPALChangeListener.iterator();
    while (i.hasNext())
    {
      Object l = i.next();
      if (UnoRuntime.areSame(l, listener)) return;
    }
    registeredPALChangeListener.add(listener);
  }

  /**
   * Diese Methode deregistriert einen XPALChangeEventListener wenn er bereits
   * registriert war.
   * 
   * Achtung: Die Methode darf nicht direkt von einem UNO-Service aufgerufen
   * werden, sondern jeder Aufruf muss �ber den EventHandler laufen. Deswegen
   * exportiert WollMuxSingleton auch nicht das
   * XPALChangedBroadcaster-Interface.
   */
  public void removePALChangeEventListener(XPALChangeEventListener listener)
  {
    Logger.debug2("WollMuxSingleton::removePALChangeEventListener()");
    Iterator i = registeredPALChangeListener.iterator();
    while (i.hasNext())
    {
      Object l = i.next();
      if (UnoRuntime.areSame(l, listener)) i.remove();
    }
  }

  /**
   * Liefert einen Iterator auf alle registrierten SenderBox-Objekte.
   * 
   * @return Iterator auf alle registrierten SenderBox-Objekte.
   */
  public Iterator palChangeListenerIterator()
  {
    return registeredPALChangeListener.iterator();
  }

  /**
   * Diese Methode liefert eine Instanz auf den EventProcessor, der u.A. dazu
   * ben�tigt wird, neue Events in die Eventqueue einzuh�ngen.
   * 
   * @return die Instanz des aktuellen EventProcessors
   */
  public EventProcessor getEventProcessor()
  {
    return EventProcessor.getInstance();
  }

  /**
   * Diese Methode liefert eine alphabethisch aufsteigend sortierte Liste aller
   * Eintr�ge der Pers�nlichen Absenderliste (PAL) in einem String-Array, wobei
   * die einzelnen Eintr�ge in der Form "<Nachname>, <Vorname> (<Rolle>)"
   * sind.
   * 
   * @see de.muenchen.allg.itd51.wollmux.XPALProvider#getPALEntries()
   */
  public String[] getPALEntries()
  {
    DJDatasetListElement[] pal = getSortedPALEntries();
    String[] elements = new String[pal.length];
    for (int i = 0; i < pal.length; i++)
    {
      elements[i] = pal[i].toString();
    }
    return elements;
  }

  /**
   * Diese Methode liefert alle DJDatasetListElemente der Pers�nlichen
   * Absenderliste (PAL) in alphabetisch aufsteigend sortierter Reihenfolge.
   * 
   * @return alle DJDatasetListElemente der Pers�nlichen Absenderliste (PAL) in
   *         alphabetisch aufsteigend sortierter Reihenfolge.
   */
  public DJDatasetListElement[] getSortedPALEntries()
  {
    // Liste der entries aufbauen.
    QueryResults data = getDatasourceJoiner().getLOS();

    DJDatasetListElement[] elements = new DJDatasetListElement[data.size()];
    Iterator iter = data.iterator();
    int i = 0;
    while (iter.hasNext())
      elements[i++] = new DJDatasetListElement((DJDataset) iter.next());
    Arrays.sort(elements);

    return elements;
  }

  /**
   * Diese Methode liefert den aktuell aus der pers�nlichen Absenderliste (PAL)
   * ausgew�hlten Absender im Format "<Nachname>, <Vorname> (<Rolle>)" zur�ck.
   * Ist die PAL leer oder noch kein Absender ausgew�hlt, so liefert die Methode
   * den Leerstring "" zur�ck. Dieser Sonderfall sollte nat�rlich entsprechend
   * durch die aufrufende Methode behandelt werden.
   * 
   * @see de.muenchen.allg.itd51.wollmux.XPALProvider#getCurrentSender()
   * 
   * @return den aktuell aus der PAL ausgew�hlten Absender als String. Ist kein
   *         Absender ausgew�hlt wird der Leerstring "" zur�ckgegeben.
   */
  public String getCurrentSender()
  {
    try
    {
      DJDataset selected = getDatasourceJoiner().getSelectedDataset();
      return new DJDatasetListElement(selected).toString();
    }
    catch (DatasetNotFoundException e)
    {
      return "";
    }
  }

  /**
   * siehe {@link WollMuxFiles#getWollmuxConf()}.
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public ConfigThingy getWollmuxConf()
  {
    return WollMuxFiles.getWollmuxConf();
  }

  /**
   * siehe {@link WollMuxFiles#getDEFAULT_CONTEXT()}.
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public URL getDEFAULT_CONTEXT()
  {
    return WollMuxFiles.getDEFAULT_CONTEXT();
  }

  /**
   * Liefert die Funktionsbibliothek, die die global definierten Funktionen
   * enth�lt.
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public FunctionLibrary getGlobalFunctions()
  {
    return globalFunctions;
  }

  /**
   * Liefert die Dialogbibliothek, die die Dialoge enth�lt, die in Funktionen
   * (Grundfunktion "DIALOG") verwendung finden.
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public DialogLibrary getFunctionDialogs()
  {
    return funcDialogs;
  }

  /**
   * siehe {@link WollMuxFiles#isDebugMode()}.
   * 
   * @author Christoph Lutz (D-III-ITD 5.1)
   */
  public boolean isDebugMode()
  {
    return WollMuxFiles.isDebugMode();
  }

  /**
   * �berpr�ft, ob von url gelesen werden kann und wirft eine IOException, falls
   * nicht.
   * 
   * @throws IOException
   *           falls von url nicht gelesen werden kann.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public static void checkURL(URL url) throws IOException
  {
    url.openStream().close();
  }

  /**
   * Hilfsklasse, die es erm�glicht, UNO-Componenten in HashMaps abzulegen; der
   * Vergleich zweier HashableComponents mit equals(...) verwendet dazu den
   * sicheren UNO-Vergleich UnoRuntime.areSame(...) und die Methode hashCode
   * wird direkt an das UNO-Objekt weitergeleitet.
   * 
   * @author lut
   */
  private static class HashableComponent
  {
    private Object compo;

    public HashableComponent(XComponent compo)
    {
      this.compo = compo;
    }

    public int hashCode()
    {
      if (compo != null) return compo.hashCode();
      return 0;
    }

    public boolean equals(Object b)
    {
      if (b != null && b instanceof HashableComponent)
      {
        HashableComponent other = (HashableComponent) b;
        return UnoRuntime.areSame(this.compo, other.compo);
      }
      return false;
    }
  }

  /**
   * Die Methode sorgt daf�r, dass sich das WollMuxSingleton die
   * Originalposition- und Dimensionen der �bergebenen UNO-Komponente (bzw.
   * deren ContainerWindow) merkt und zu einem sp�teren Zeitpunkt mit der
   * methode restoreOriginalWindowPosSize(...) wieder auf den Originalwert
   * zur�ckgesetzt werden kann. ACHTUNG: WollMux-Singleton h�lt so lange eine
   * Referenz auf die Komponente, bis sie mit restoreOriginalWindowPosSize()
   * wieder freigegeben wird. Der Mechanismus wird derzeit verwendet, um die
   * Fensterposition des Office-Fensters vor dem Verschieben durch die FormGUI
   * wieder r�cksetzen zu k�nnen.
   * 
   * @param compo
   *          Die Komponente, deren ContainerWindow die original Position und
   *          Gr��e liefert.
   */
  public void storeOriginalWindowPosSize(XComponent compo)
  {
    Rectangle origPosSize = null;
    try
    {
      origPosSize = UNO.XModel(compo).getCurrentController().getFrame()
          .getContainerWindow().getPosSize();
    }
    catch (java.lang.Exception e)
    {
    }
    if (compo != null && origPosSize != null)
    {
      originalWindowPosSizes.put(new HashableComponent(compo), origPosSize);
    }
  }

  /**
   * Die Methode setzt die Position und Gr��e der �bergebenen UNO-Komponente
   * (bzw. deren ContainerWindow) wieder auf den Stand zum Zeitpunkt des Aufrufs
   * der Methode storeOriginalWindowPosSize zur�ck und l�scht alle Referenzen
   * auf die Komponente aus dem WollMuxSingleton. Falls die Komponente nicht
   * bekannt ist (da mit Ihr kein storeOriginalWindowPosSize-Aufruf erfolgte),
   * geschieht nichts.
   * 
   * @param compo
   *          Die Komponente, deren ContainerWindow wieder auf die alte Position
   *          und Gr��e gesetzt werden soll.
   */
  public void restoreOriginalWindowPosSize(XComponent compo)
  {
    Rectangle ps = (Rectangle) originalWindowPosSizes
        .remove(new HashableComponent(compo));
    if (ps != null)
      try
      {
        UNO.XModel(compo).getCurrentController().getFrame()
            .getContainerWindow().setPosSize(
                ps.X,
                ps.Y,
                ps.Width,
                ps.Height,
                PosSize.POSSIZE);
      }
      catch (java.lang.Exception e)
      {
      }
  }
}
