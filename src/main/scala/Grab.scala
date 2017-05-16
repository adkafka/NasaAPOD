import spray.json._ //Json parser
import scala.io.Source //Read files, local and remote

object Grab { 
    def main(args: Array[String]) = {
        MediaGrabber.FetchJson()
        MediaGrabber.FetchJson("2017-05-15")
    }
}


object MediaGrabber{
    def GetAPIKey: String = {
        val key = Source.fromFile("API_KEY")
        try key.mkString.stripLineEnd finally key.close()
    }
    def FetchJson(date : String = "") = {
        val API_KEY = MediaGrabber.GetAPIKey
        var URL = "https://api.nasa.gov/planetary/apod"
        URL = URL.concat("?api_key=%s".format(API_KEY))
        if (date != ""){
            URL = URL.concat("&date=%s".format(date))
        }
        println(URL)
        try{
            val json = Source.fromURL(URL)
            val jsonAst = JsonParser(json.mkString)
            println(jsonAst)
        }
        catch{
            case io : java.io.IOException  => println("Server returned bad HTTP response code\n"+io)
            case e  : Throwable => e.printStackTrace
        }
    }
}

case class ImageGrabber(url: String){

}
case class VideoGrabber(url: String){

}
