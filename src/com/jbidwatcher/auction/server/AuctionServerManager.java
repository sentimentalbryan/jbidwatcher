package com.jbidwatcher.auction.server;
/*
 * Copyright (c) 2000-2007, CyberFOX Software, Inc. All Rights Reserved.
 *
 * Developed by mrs (Morgan Schweers)
 */

import com.jbidwatcher.queue.MessageQueue;
import com.jbidwatcher.queue.MQFactory;
import com.jbidwatcher.xml.XMLSerialize;
import com.jbidwatcher.xml.XMLElement;
import com.jbidwatcher.xml.XMLParseException;
import com.jbidwatcher.search.SearchManagerInterface;
import com.jbidwatcher.util.ErrorManagement;
import com.jbidwatcher.auction.EntryManager;
import com.jbidwatcher.auction.AuctionEntry;
import com.jbidwatcher.config.JConfigTab;

import java.util.*;
import java.net.*;

public class AuctionServerManager implements XMLSerialize, MessageQueue.Listener {
  private List<AuctionServerListEntry> _serverList;
  private List<JConfigTab> _configTabList = null;
  private final static AuctionServerManager _instance = new AuctionServerManager();
  private static EntryManager _em = null;

  private static final boolean uber_debug = false;

  static {
    MQFactory.getConcrete("auction_manager").registerListener(_instance);
  }

  public static void setEntryManager(EntryManager newEM) { _em = newEM; }

  private static class AuctionServerListEntry {
    private String _serverName;
    private AuctionServer _aucServ;

    private AuctionServerListEntry(String name, AuctionServer server) {
      _serverName = name;
      _aucServ = server;
    }

    public AuctionServer getAuctionServer() {
      return _aucServ;
    }

    public String getName() {
      return _serverName;
    }
  }

  public AuctionServer getServerByName(String name) {
    for (AuctionServerListEntry a_serverList : _serverList) {
      AuctionServer as = (a_serverList).getAuctionServer();
      if (as.getName().equals(name)) return as;
    }

    return null;
  }

  private AuctionServerManager() {
    _serverList = new ArrayList<AuctionServerListEntry>();
  }

  /** 
   * @brief Load all the auction servers.
   * 
   * BUGBUG - Refactor this to use the XMLSerializeSimple if at all possible!
   *
   * @param inXML - The XML source to load from.
   * @noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException,StringContatenationInLoop
   */
  public void fromXML(XMLElement inXML) {
    Iterator<XMLElement> serversStep = inXML.getChildren();

    while(serversStep.hasNext()) {
      XMLElement perServer = serversStep.next();
      //  Only process the 'server' entries.
      if(perServer.getTagName().equals("server")) {
        AuctionServer newServer = null;
        String serverName = perServer.getProperty("NAME", null);
        if(serverName != null) {
          newServer = getServerByName(serverName);
          if(newServer == null) {
            try {
              Class<?> newClass = Class.forName(serverName + "Server");
              newServer = (AuctionServer) newClass.newInstance();
              newServer = addServer(serverName, newServer);
            } catch(ClassNotFoundException cnfe) {
              ErrorManagement.handleException("Failed to load controller class for server " + serverName + '.', cnfe);
              throw new XMLParseException(inXML.getTagName(), "Failed to load controller class for server " + serverName + '.');
            } catch(InstantiationException ie) {
              ErrorManagement.handleException("Failed to instantiate server " + serverName + '.', ie);
              throw new XMLParseException(inXML.getTagName(), "Failed to instantiate server for " + serverName + '.');
            } catch(IllegalAccessException iae) {
              ErrorManagement.handleException("Illegal access when instantiating server for " + serverName + '.', iae);
              throw new XMLParseException(inXML.getTagName(), "Illegal access when instantiating server for " + serverName + '.');
            }
          }
        }

        if(newServer != null) {
          newServer.setEntryManager(_em);
          try {
            newServer.fromXML(perServer);
          } catch(XMLParseException e) {
            ErrorManagement.handleException("Parse exception: ", e);
          }
        }
      }
    }
  }

  /** 
   * @brief Serialize access to the time updating function, so that
   * everybody in the world doesn't try to update the time all at
   * once, like they used to.  Four threads trying to update the time
   * all together caused some nasty errors.
   */
  public void messageAction(Object deQ) {
    String cmd = (String)deQ;

    if(cmd.equals("TIMECHECK")) {
      AuctionServerInterface defaultServer = getDefaultServer();

      defaultServer.reloadTimeNow();

      long servTime = defaultServer.getOfficialServerTimeDelta();
      Date now = new Date(System.currentTimeMillis() + servTime);
      MQFactory.getConcrete("Swing").enqueue("Server time is now: " + now);

      try {
        boolean done = false;
        final MessageQueue aucManagerQ = MQFactory.getConcrete("auction_manager");
        while(!done) {
          cmd = aucManagerQ.dequeue();
          if(!cmd.equals("TIMECHECK")) done=true;
        }
        //  Re-enqueue the last one, because it must not have been
        //  another TIMECHECK command!
        aucManagerQ.enqueue(cmd);
      } catch(NoSuchElementException nsee) {
        //  Nothing really to do, this just means we cleaned out the
        //  list before finding a non-timecheck value.
        if(uber_debug) ErrorManagement.logDebug("No Such Element caught.");
      }
    }
  }

  public String getDefaultServerTime() {
    AuctionServerInterface defaultServer = getDefaultServer();
    return defaultServer.getTime();
  }

  public XMLElement toXML() {
    XMLElement xmlResult = new XMLElement("auctions");
    int aucCount = 0;

    for (AuctionServerListEntry asle : _serverList) {
      aucCount += asle.getAuctionServer().getAuctionCount();

      xmlResult.addChild(asle.getAuctionServer().toXML());
    }

    xmlResult.setProperty("count", Integer.toString(aucCount));

    return xmlResult;
  }

  public void delete_entry(AuctionEntry ae) {
    AuctionServer aucserv = ae.getServer();
    if(aucserv != null) {
      aucserv.unregisterAuction(ae);
    }
  }

  public void add_entry(AuctionEntry ae) {
    AuctionServer aucserv = ae.getServer();
    if(aucserv != null) {
      aucserv.registerAuction(ae);
    }
  }

  public static AuctionServerManager getInstance() {
    return _instance;
  }

  private AuctionServer addServerNoUI(String inName, AuctionServer aucServ) {
    for (AuctionServerListEntry asle : _serverList) {
      if (asle.getAuctionServer() == aucServ || inName.equals(asle.getName())) {
        return (asle.getAuctionServer());
      }
    }

    _serverList.add(new AuctionServerListEntry(inName, aucServ));

    return(aucServ);
  }

  public AuctionServer addServer(String inName, AuctionServer aucServ) {
    AuctionServer as = addServerNoUI(inName, aucServ);
    if(as == aucServ) {
      if(_configTabList != null) {
        _configTabList.add(aucServ.getConfigurationTab());
      }
    }

    return(as);
  }

  private AuctionServer addServer(AuctionServer aucServ) {
    return(addServer(aucServ.getName(), aucServ));
  }

  //  Handle the case of '198332643'.  (For 'paste auction').
  public AuctionServer getServerForIdentifier(String auctionId) {
    for (AuctionServerListEntry a_serverList : _serverList) {
      AuctionServer as = (a_serverList).getAuctionServer();

      if (as.checkIfIdentifierIsHandled(auctionId)) {
        return (addServer(as));
      }
    }

    return null;
  }

  public AuctionServer getServerForUrlString(String strURL) {
    for (AuctionServerListEntry a_serverList : _serverList) {
      AuctionServer as = (a_serverList).getAuctionServer();
      URL serverAddr = AuctionServer.getURLFromString(strURL);

      if (as.doHandleThisSite(serverAddr)) {
        return (addServer(as));
      }
    }

    ErrorManagement.logDebug("No matches for getServerForUrlString(" + strURL + ')');
    return null;
  }

  public void addAuctionServerMenus() {
    for (AuctionServerListEntry a_serverList : _serverList) {
      AuctionServer as = (a_serverList).getAuctionServer();
      as.establishMenu();
    }
  }

  public AuctionServer getDefaultServer() {
    Iterator<AuctionServerListEntry> it = _serverList.iterator();
    if(it.hasNext()) {
      return (it.next()).getAuctionServer();
    }

    return null;
  }

  public void addSearches(SearchManagerInterface searchManager) {
    for (AuctionServerListEntry a_serverList : _serverList) {
      AuctionServer as = (a_serverList).getAuctionServer();

      as.addSearches(searchManager);
    }
  }

  public void cancelSearches() {
    for (AuctionServerListEntry a_serverList : _serverList) {
      AuctionServer as = (a_serverList).getAuctionServer();
      as.cancelSearches();
    }
  }

  public List<JConfigTab> getServerConfigurationTabs() {
    //  Always rebuild, so as to fix a problem on first-startup.
    _configTabList = new ArrayList<JConfigTab>();
    for (AuctionServerListEntry a_serverList : _serverList) {
      AuctionServer as = (a_serverList).getAuctionServer();
      _configTabList.add(as.getConfigurationTab());
    }
    return _configTabList;
  }
}
