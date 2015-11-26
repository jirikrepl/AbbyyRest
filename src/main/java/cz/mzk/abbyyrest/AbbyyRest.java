package cz.mzk.abbyyrest;

import cz.mzk.abbyyrest.pojo.QueueItem;
import org.apache.commons.io.FileUtils;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by rumanekm on 5.5.15.
 */
@Path("/ocr")
public class AbbyyRest {

    String pathIn = System.getenv("ABBYY_IN");
    String pathOut = System.getenv("ABBYY_OUT");
    String pathEx = System.getenv("ABBYY_EXCEPTION");
    String pathTmp = System.getenv("ABBYY_TMP");

    @GET
    @Path("/state/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getState(@PathParam("id") String id) {

        QueueItem searchedItem = new QueueItem();
        searchedItem.setId(id);

        if (!id.matches("[a-fA-F0-9]{32}")) {
            searchedItem.setMessage("Zadane ID nema validni tvar MD5.");
            searchedItem.setState(QueueItem.STATE_ERROR);
            return Response.status(Status.BAD_REQUEST).entity(searchedItem).build();
        }
        File txt = isPresentIn(id, "txt", pathOut);
        File xml = isPresentIn(id, "xml", pathOut);

        if (xml != null && txt != null) {
            File deletedFlag = isPresentIn(id, "flg", pathTmp);
            if (deletedFlag != null) deletedFlag.delete();
            searchedItem.setState(QueueItem.STATE_DONE);
            searchedItem.setMessage("Zpracovano.");
            return Response.ok(searchedItem).build();

        } else if (isPresentIn(id, "result.xml", pathEx) != null) {
            File deletedFlag = isPresentIn(id, "flg", pathTmp);
            if (deletedFlag != null) deletedFlag.delete();
            searchedItem.setMessage("Beh ABBYY OCR skoncil vyjimkou.");
            searchedItem.setState(QueueItem.STATE_ERROR);
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(searchedItem).build();

        } else if (isPresentIn(id, "flg", pathTmp) != null) {
            searchedItem.setState(QueueItem.STATE_PROCESSING);
            searchedItem.setMessage("Ve fronte ke zpracovani.");
            return Response.ok(searchedItem).build();

        } else {
            searchedItem.setMessage("Zadny zaznam pro zadane ID.");
            searchedItem.setState(QueueItem.STATE_ERROR);
            return Response.status(Status.NOT_FOUND).entity(searchedItem).build();
        }
    }

    @POST
    @Path("/in")
    @Consumes({"image/jpeg", "image/jp2"})
    @Produces(MediaType.APPLICATION_JSON)
    public Response putInQueue(InputStream is, @Context HttpHeaders headers) {

        String contentType = headers.getRequestHeader("Content-Type").get(0);
        MessageDigest md;
        QueueItem pushedItem = new QueueItem();
        pushedItem.setId(null);
        pushedItem.setState(QueueItem.STATE_ERROR);

        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            pushedItem.setMessage("Nepodarilo se inicializovat vypocet MD5.");
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(pushedItem).build();
        }

        DigestInputStream dis = new DigestInputStream(is, md);
        DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");
        Date date = new Date();
        String tmpname = dateFormat.format(date);
        File tmpfile = new File(pathTmp + File.separator + tmpname + ".tmp");
        try {
            FileUtils.copyInputStreamToFile(dis, tmpfile);
        } catch (IOException e) {
            pushedItem.setMessage("Zapis souboru do vstupni slozky selhal.");
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(pushedItem).build();
        }

        byte[] digest = md.digest();
        StringBuffer nameBuilder = new StringBuffer();
        for (int i = 0; i < digest.length; i++) {
            String hex = Integer.toHexString(0xff & digest[i]);
            if (hex.length() == 1) nameBuilder.append('0');
            nameBuilder.append(hex);
        }
        String extension = contentType.split("/")[1];
        if (extension.equalsIgnoreCase("jpeg")) {
            extension = "jpg";
        }
        String name = nameBuilder.toString();
        pushedItem.setId(name);
        String fullName = name + "." + extension;
        File fnamed = new File(pathIn + File.separator + fullName);

        File flg = new File(pathTmp + File.separator + name + ".flg");
        try {
            flg.createNewFile();
        } catch (IOException e) {
            pushedItem.setMessage("Vytvoreni .flg znacky selhalo.");
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(pushedItem).build();
        }

        if (fnamed.exists()) {
            pushedItem.setState(QueueItem.STATE_PROCESSING);
            pushedItem.setMessage("Polozka je jiz ve vstupni slozce.");
            tmpfile.delete();
//            return Response.status(Status.CONFLICT).entity(pushedItem).build();
            return Response.ok(pushedItem).build();
        }

        if (!tmpfile.renameTo(fnamed)) {
            tmpfile.delete();
            pushedItem.setMessage("Presun z docasne do vstupni slozky selhal.");
            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(pushedItem).build();
        }
        tmpfile.delete();
        pushedItem.setState(QueueItem.STATE_PROCESSING);
        pushedItem.setMessage("Zarazeno do fronty.");
        return Response.ok(pushedItem).build();
    }

    @GET
    @Path("/product/{type}/{id}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_XML, MediaType.TEXT_PLAIN})
    public Response getProduct(@PathParam("id") String id, @PathParam("type") String type) {

        QueueItem requestedItem = new QueueItem();
        requestedItem.setId(id);
        requestedItem.setState(QueueItem.STATE_ERROR);

        String mType;
        if (type.equalsIgnoreCase("alto")) {
            mType = MediaType.TEXT_XML;
            type = "xml";
        } else if (type.equalsIgnoreCase("txt")) {
            mType = MediaType.TEXT_PLAIN;
        } else {
            requestedItem.setMessage("Byl pozadovan jiny format nez \"alto\" nebo \"txt\".");
            return Response.status(Status.BAD_REQUEST).entity(requestedItem).build();
        }


        if (!id.matches("[a-fA-F0-9]{32}")) {
            requestedItem.setMessage("Zadane ID nema validni tvar MD5.");
            return Response.status(Status.BAD_REQUEST).entity(requestedItem).build();
        }

        File f = isPresentIn(id, type, pathOut);

        if (f == null) {
            requestedItem.setMessage("Pozadovany produkt neni k dispozici");
            return Response.status(Status.NOT_FOUND).entity(requestedItem).type(MediaType.APPLICATION_JSON).build();
        } else {
            FileInputStream fis;
            try {
                fis = new FileInputStream(f);
            } catch (FileNotFoundException e) {
                requestedItem.setMessage("Soubor se nepodarilo precist");
                return Response.status(Status.INTERNAL_SERVER_ERROR).entity(requestedItem).type(MediaType.APPLICATION_JSON).build();
            }
            return Response.ok().entity(fis).type(mType).build();
        }
    }

    @DELETE
    @Path("/delete/{id}")
    @Produces({MediaType.APPLICATION_JSON})
    public Response delete(@PathParam("id") String id) {
        QueueItem deletedItem = new QueueItem();
        deletedItem.setState(QueueItem.STATE_ERROR);
        String message = "";

        File f = isPresentIn(id, "jpg", pathIn);
        if (f != null) {
            if (f.delete()) {
                message = message + " IN";
            } else {
                deletedItem.setMessage("Selhalo smazani ze slozky IN.");
                return Response.status(Status.INTERNAL_SERVER_ERROR).entity(deletedItem).build();
            }
        }

        f = isPresentIn(id, "flg", pathTmp);
        if (f != null) {
            if (f.delete()) {
                message = message + " TMP";
            } else {
                deletedItem.setMessage("Selhalo smazani flagu ze slozky TMP.");
                return Response.status(Status.INTERNAL_SERVER_ERROR).entity(deletedItem).build();
            }
        }

        f = isPresentIn(id, "txt", pathOut);
        if (f != null) {
            if (f.delete()) {
                f = isPresentIn(id, "xml", pathOut);
                if (f != null) {
                    if (f.delete()) {
                        f = isPresentIn(id, "result.xml", pathOut);
                        if (f != null) {
                            if (f.delete()) {
                                message = message + " OUT";
                            } else {
                                deletedItem.setMessage("Selhalo smazani RESULT ze slozky OUT.");
                                return Response.status(Status.INTERNAL_SERVER_ERROR).entity(deletedItem).build();
                            }
                        } else {
                            deletedItem.setMessage("Selhalo smazani ALTO ze slozky OUT.");
                            return Response.status(Status.INTERNAL_SERVER_ERROR).entity(deletedItem).build();
                        }
                    }
                } else {
                    deletedItem.setMessage("Selhalo smazani TXT ze slozky OUT.");
                    return Response.status(Status.INTERNAL_SERVER_ERROR).entity(deletedItem).build();
                }
            }
        }
            f = isPresentIn(id, "xml", pathEx);
            if (f != null) {
                if (f.delete()) {
                    message = message + " EXCEPTION";
                } else {
                    deletedItem.setMessage("Selhalo smazani ze slozky EXCEPTION.");
                }
            }

            if (message != "") {
                deletedItem.setMessage("Smazano z" + message + ".");
                deletedItem.setState(QueueItem.STATE_DELETED);
                return Response.ok().entity(deletedItem).build();
            }

            deletedItem.setMessage("Polozka nebyla nalezena.");
            return Response.status(Status.NOT_FOUND).entity(deletedItem).build();
        }

    // v současném stavu nemůže fungovat (každý soubor má nějaký suffix)
    // TODO opravit mazání z IN (Jaké jsou možné suffixy? Jen .jpg a .jp2?)
    private File isPresentIn(String id, String path) {
        return isPresentIn(id, "", path);
    }

    private File isPresentIn(String id, String suffix, String path) {
        File f = new File(path + File.separator + id + "." + suffix);
        if (f.exists() && !f.isDirectory()) {
            return f;
        }
        return null;
    }

}
