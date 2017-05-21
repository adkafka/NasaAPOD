//import spray.json._ //Json parser
import org.json4s._
import org.json4s.native.JsonMethods._
import scala.io.Source //Read files, local and remote
import java.time.LocalDate //DateTime ops
import java.time.format.DateTimeFormatter //Format datetime

object Grab { 
    // TODO, multiple parameter options
    /* 
     * Grab [start yyyy-mm-dd] [end yyyy-mm-dd]
         * Default to both == today
             * Or, start date is last file in directory... may introduce errors
         * yyyy-mm-dd
         * Start on begin date. Create Date obj, convert to string with format.
         * Use + 1.days (http://alvinalexander.com/scala/scala-number-nnumeric-date-formatting-casting-examples)
     * Add configuration to one file
         * API key
         * Destination Directory
         * Directory to put sym links in
     */

    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    def main(args: Array[String]) = {
        // Grab start and end dates from agrs
        val (startDate, endDate) = parseArgs(args)
        printf("Grabbing NASA POD from %s -> %s\n",startDate.format(fmt),
            endDate.format(fmt))

        // For every date from start to end inclusive...
        val numberOfDays = startDate.until(endDate).getDays()
        for (diff <- 0 to numberOfDays)
            // Grab the file if we don't already have it
            CheckAndGrab(startDate.plusDays(diff))



        //MediaGrabber.Grab()
        //MediaGrabber.Grab("2017-05-15")
    }

    def CheckAndGrab(date: LocalDate) = {
        printf("Grabbing for day: %s\n",date.format(fmt))
    }

    def parseArgs(args: Array[String]) = {
        /* For now, default to current date. */
        val startDate = if (args.length>=1) LocalDate.parse(args(0),fmt) 
                        else LocalDate.now
        val endDate   = if (args.length>=2) LocalDate.parse(args(1),fmt) 
                        else LocalDate.now
        if (endDate.isBefore(startDate))
            error("Error, you must provide an end date that is after the start Date")

        (startDate,endDate)
    }

    def error(error_msg: String) = {
        System.err.println(error_msg)
        System.exit(1)
    }
}


object MediaGrabber{
    def GetAPIKey: String = {
        val key = Source.fromFile("API_KEY")
        try key.mkString.stripLineEnd finally key.close()
    }
    def FetchJson(date : String = "") : JsonAST.JValue = {
        val API_KEY = MediaGrabber.GetAPIKey
        var URL = "https://api.nasa.gov/planetary/apod"
        URL = URL.concat("?api_key=%s".format(API_KEY))
        if (date != ""){
            URL = URL.concat("&date=%s".format(date))
        }
        URL = URL.concat("&hd=True")
        var jsonAst : JsonAST.JValue = null
        try{
            val json = Source.fromURL(URL)
            jsonAst = parse(json.mkString)
            println(pretty(render(jsonAst)))
        }
        catch{
            case io : java.io.IOException  => println("Server returned bad HTTP response code\n"+io)
            case e  : Throwable => e.printStackTrace
        }

        return jsonAst
    }
    def Grab(date : String = "") = {
        val jsonTree = FetchJson(date)

        implicit val formats = DefaultFormats
        val resp = jsonTree.extract[Response]

        printf("Date: %s\nTitle: %s\nType: %s\n",resp.date,resp.title,resp.media_type)

        resp.media_type match {
            case "image" => GrabImage(resp)
            case "video" => GrabVideo(resp)
            case default => printf("Unknown media type found: %s\n",default)
        }
    }
    def GrabImage(resp: Response){

    }
    def GrabVideo(resp: Response){
    }
}

case class Response(
    copyright : String = "",
    date : String = "",
    explanation : String = "",
    hdurl : String = "",
    media_type : String = "",
    service_version : String = "",
    title : String = "",
    url : String = "" )
