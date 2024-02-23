reconnectionIntervalSec (integer)
    Select the reconnection interval. If the connection to the SLE provider breaks, Yamcs will attempt to reconnect after that many seconds.
    
    Default: ``30`` (seconds)

roundRobinIntervalMillis (integer)
    If multiple endpoints are specified, this is the amount of time to wait before connecting to the next one. After all endpoints have been exhausted the parameter above applies for connecting againt to the first endpoint in the list. If only one endpoint is specified, this parameter has no effect.
    
    Default: ``100`` (milliseconds)
