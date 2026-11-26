// La interfaz del servicio de BRouter (https://github.com/abrensch/brouter, licencia MIT), tal cual:
// el paquete y el nombre tienen que coincidir con los suyos para poder hablar con él. Los
// parámetros están explicados en su fichero original; los que usa IGN GPS, en data/Routing.kt.
package btools.routingapp;

interface IBRouterService {
    String getTrackFromParams(in Bundle params);
}
