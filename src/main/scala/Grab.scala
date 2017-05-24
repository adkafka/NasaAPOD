//import spray.json._ //Json parser
import org.json4s._
import org.json4s.native.JsonMethods._
import scala.io.Source //Read files, local and remote
import java.time.LocalDate //DateTime ops
import java.time.format.DateTimeFormatter //Format datetime

import sys.process._ //Piping
import java.net.URL //Donwload
import java.io.File // Save
import scala.language.postfixOps //Allow !! at end of pipe call

import java.nio.file._ // Create hard link

object Grab { 
    // TODO
    /* 
     * Grab [start yyyy-mm-dd] [end yyyy-mm-dd]
         * Default to both == today
             * Or, start date is last file in directory... may introduce errors
         * yyyy-mm-dd
     * Add configuration to one file
         * API key
         * Destination Directory
         * Directory to put sym links in
     * Retry on HTTP failures?
         * Abstract HTTP stuff to one object. 
         * Have to string and to file methods
     */

    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    // Put these in a settings file
    val pod_dir = "/Users/adam/Desktop/NasaDailyPics2/"
    val screensaver_dir = "/Users/adam/Desktop/ScreenSaverPics/"

    def main(args: Array[String]) = {
        // Grab start and end dates from agrs
        val (startDate, endDate) = parseArgs(args)
        printf("[*] Grabbing NASA POD from %s -> %s\n",startDate.format(fmt),
            endDate.format(fmt))

        // For every date from start to end inclusive...
        val numberOfDays = java.time.temporal.ChronoUnit.DAYS.between(startDate,endDate).toInt
        println(numberOfDays)
        for (diff <- 0 to numberOfDays){
            // Grab the file if we don't already have it
            MediaGrabber.CheckAndGrab(startDate.plusDays(diff))
        }
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
        System.err.println("[!] "+error_msg)
        System.exit(1)
    }
}


object MediaGrabber{
    def CheckAndGrab(date: LocalDate) = {
        val date_str = date.format(Grab.fmt)
        val dest_dir = new java.io.File(Grab.pod_dir)
        // Check if file is present in pod_dir
        val matches = dest_dir.listFiles.filter(_.isFile).toList.filter{ file =>
            file.getName.startsWith(date_str)
        }
        /*
        // If it is, grab image for this date
        if(matches.length==0){
            */
            GrabMedia(date_str)
            /*
        }
        else{
            // Otherwise, continue
            printf("[-] File already exists for date: %s, continuing on next date\n",
                date_str)
        }
        */

    }

    def GetAPIKey: String = {
        val key = Source.fromFile("API_KEY")
        try key.mkString.stripLineEnd finally key.close()
    }

    def FetchJson(date : String = "") : Option[JsonAST.JValue] = {
        val API_KEY = MediaGrabber.GetAPIKey
        var URL = "https://api.nasa.gov/planetary/apod"
        URL = URL.concat("?api_key=%s".format(API_KEY))
        if (date != ""){
            URL = URL.concat("&date=%s".format(date))
        }
        URL = URL.concat("&hd=True")
        try{
            val json = Source.fromURL(URL)
            Some(parse(json.mkString))
        }
        catch{
            case io : java.io.IOException  => 
                printf("[-] Server returned bad HTTP response code:\n\t%s\n",io)
                None
            case e  : Throwable => 
                e.printStackTrace
                None
        }

    }

    def GrabMedia(date : String = "") = {
        printf("[+] Grabbing for day: %s\n",date)
        FetchJson(date) match {
            case Some(jsonTree) => {
                implicit val formats = DefaultFormats
                val resp = jsonTree.extract[Response]

                printf("[+]\tDate: %s\n\tTitle: %s\n\tType: %s\n",resp.date,
                    resp.title,resp.media_type)

                resp.media_type match {
                    case "image" => GrabImage(resp)
                    case "video" => GrabVideo(resp)
                    case default => printf("[!] Unknown media type found: %s\n",default)
                }
                printf("[+] Done\n")
            }
            case None  => printf("[-] Could not query NASA API\n")
        }
    }
    
    def MakeFilename(resp: Response): String = {
        resp.date+"-"+resp.title.replaceAll("[^A-Za-z0-9]","_")
    }

    def GrabImage(resp: Response) = {
        val url = if (resp.hdurl!="") resp.hdurl else resp.url
        val file_ext = url.split('.').last
        val out_fn = MakeFilename(resp)+"."+file_ext
        val out_full = Grab.pod_dir+out_fn
        printf("[+] Grabbing image from URL: %s\n",resp.hdurl)
        printf("[+] Saving as filename: %s\n", out_full)

        // Download image and save
        val exit_status = new URL(resp.hdurl) #> new File(out_full) !

        if (exit_status != 0){
            printf("[-] Download and save returned with exit status: %d\n",
                exit_status)
        }

        // Link to screen saver path
        try{
            val destination = Grab.screensaver_dir+out_fn
            Files.createLink(Paths.get(destination), Paths.get(out_full));
        }catch{
            case exists : java.nio.file.FileAlreadyExistsException => {
                printf("[-] Hard link already exists\n")
            }
            case e : Throwable => {
                printf("[!] Error creating link!")
                e.printStackTrace
            }
        }
    }

    def GrabVideo(resp: Response) = {
        val out_fn = MakeFilename(resp)
        val out_full = Grab.pod_dir+out_fn

        val exit_status = "youtube-dl -f 38/37/84/22/85/82/83/18/17/35/34/5 "+
                            "-o "+out_full+".%(ext)s"+
                            " --restrict-filenames "+resp.url !

        if (exit_status != 0){
            printf("Youtube-dl failed with exit status: %d\n",exit_status)
            printf("Skipping this day because there is no easy way to download media\n")
        }

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
