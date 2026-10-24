package com.castor.mobile;

/** Uma TV/dispositivo de transmissão encontrado na rede (ou um alvo do servidor Castor remoto). */
public final class CastDevice {
    public static final int DLNA = 0;
    public static final int CHROMECAST = 1;
    public static final int REMOTE = 2;

    public final int kind;
    public final String id;
    public final String name;
    public final String host;
    public final int port;
    /** DLNA: URL de controle do AVTransport. REMOTE: id do dispositivo no servidor. */
    public final String control;
    /** DLNA: tipo completo do serviço AVTransport. */
    public final String service;
    /** DLNA: URL/serviço RenderingControl, quando a TV os anuncia. */
    public final String renderingControl;
    public final String renderingService;

    public CastDevice(int kind, String id, String name, String host, int port, String control, String service) {
        this(kind, id, name, host, port, control, service, "", "");
    }

    public CastDevice(int kind, String id, String name, String host, int port, String control, String service,
                      String renderingControl, String renderingService) {
        this.kind = kind;
        this.id = id;
        this.name = name == null || name.isEmpty() ? id : name;
        this.host = host == null ? "" : host;
        this.port = port;
        this.control = control == null ? "" : control;
        this.service = service == null ? "" : service;
        this.renderingControl = renderingControl == null ? "" : renderingControl;
        this.renderingService = renderingService == null ? "" : renderingService;
    }

    public String label() {
        switch (kind) {
            case CHROMECAST:
                return name + "  ·  Chromecast";
            case REMOTE:
                return name + "  ·  via servidor Castor";
            default:
                return name + "  ·  DLNA";
        }
    }
}
