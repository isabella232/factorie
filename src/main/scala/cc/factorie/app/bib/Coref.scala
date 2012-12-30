package cc.factorie.app.bib
import cc.factorie.app.bib.parser._
import cc.factorie._
import cc.factorie.app.bib.experiments._
import cc.factorie.util.DefaultCmdOptions
import app.nlp.coref._
import db.mongo._
import com.mongodb.{DB, Mongo}
import collection.mutable.{HashSet, HashMap, LinkedHashMap, ArrayBuffer}
import java.io.{FileInputStream, InputStreamReader, BufferedReader, File}
import javax.xml.parsers.{DocumentBuilder, DocumentBuilderFactory}
import la.DenseTensor
import optimize.{WeightsAveraging, AROW, MIRA, SampleRankTrainer}
import org.w3c.dom.{Node, NodeList, Document}

trait BibEntity{
  var dataSource:String=""
  var paperMentionId:String=null
}
class InfoBag(val entity:Entity) extends BagOfWordsVariable(Nil, null) with EntityAttr
class ChildCountsForBag(initialWords:Iterable[String]=Nil,initialMap:Map[String,Double]=null) extends BagOfWordsVariable(initialWords,initialMap)
class AuthorFLNameCanopy(val entity:AuthorEntity) extends CanopyAttribute[AuthorEntity] {
  lazy val canopyName:String=FeatureUtils.normalizeName((initial(entity.entityRoot.asInstanceOf[AuthorEntity].fullName.firstName)+entity.entityRoot.asInstanceOf[AuthorEntity].fullName.lastName).toLowerCase)
  //def canopyName:String=(initial(entity.fullName.firstName)+entity.fullName.lastName).toLowerCase
  def initial(s:String):String = if(s!=null && s.length>0)s.substring(0,1) else ""
}
class AuthorFMLNameCanopy(val entity:AuthorEntity) extends CanopyAttribute[AuthorEntity] {
  lazy val canopyName:String=FeatureUtils.normalizeName((initial(entity.entityRoot.asInstanceOf[AuthorEntity].fullName.firstName)+initial(entity.entityRoot.asInstanceOf[AuthorEntity].fullName.middleName)+entity.entityRoot.asInstanceOf[AuthorEntity].fullName.lastName).toLowerCase)
  //def canopyName:String=(initial(entity.fullName.firstName)+entity.fullName.lastName).toLowerCase
  def initial(s:String):String = if(s!=null && s.length>0)s.substring(0,1) else ""
}
class PaperTitleCanopy(val entity:PaperEntity) extends CanopyAttribute[PaperEntity]{
  def cleanTitle(s:String) = s.toLowerCase.replaceAll("[^a-z0-9 ]","").replaceAll(" +"," ")
  def canopyName:String = cleanTitle(entity.entityRoot.asInstanceOf[PaperEntity].title.value)
}
//Attributes specific to REXA authors
class FullName(val entity:Entity,f:String,m:String,l:String,su:String=null) extends SeqVariable[String](Seq(f,m,l,su)) with EntityAttr {
  def setFirst(s:String)(implicit d:DiffList) = update(0,s)
  def setMiddle(s:String)(implicit d:DiffList) = update(1,s)
  def setLast(s:String)(implicit d:DiffList) = update(2,s)
  def setSuffix(s:String)(implicit d:DiffList) = update(3,s)
  def setFullName(that:FullName)(implicit d:DiffList) = {
    if(firstName!=that.firstName)setFirst(that.firstName)(null)
    if(middleName!=that.middleName)setMiddle(that.middleName)(null)
    if(lastName!=that.lastName)setLast(that.lastName)(null)
  }
  def firstName = value(0)
  def middleName = value(1)
  def lastName = value(2)
  def suffix = value(3)
  //def domain = GenericDomain
  override def toString:String = {
    val result = new StringBuffer
    if(firstName!=null && firstName.length>0)result.append(firstName+" ")
    if(middleName!=null && middleName.length>0)result.append(middleName+" ")
    if(lastName!=null && lastName.length>0)result.append(lastName+" ")
    if(suffix!=null && suffix.length>0)result.append(suffix)
    result.toString.trim
  }
}
//paper attributes
class TitleHash(val entity:Entity,title:String) extends StringVariable(title) with EntityAttr{
  //override def := (s:String) = super.:=(FeatureUtils.titleHash(title))
  //override def set(s:String)(implicit d:DiffList) = super.set(FeatureUtils.titleHash(s))(d)
}
class Title(val entity:Entity,title:String="") extends StringVariable(title) with EntityAttr{
  val titleHash:TitleHash = new TitleHash(entity,FeatureUtils.titleHash(title))
  override def set(s:String)(implicit d:DiffList) = {super.set(s)(d);titleHash.set(FeatureUtils.titleHash(s))(d)}
}
class Year(val entity:Entity,year:Int) extends IntegerVariable(year) with EntityAttr
class VenueName(val entity:Entity,venueName:String) extends StringVariable(venueName) with EntityAttr
class BagOfAuthors(val entity:Entity,authorBag:Map[String,Double]=null) extends BagOfWordsVariable(Nil,authorBag) with EntityAttr{
  def addAuthor(author:AuthorEntity,order:Int)(implicit d:DiffList) = this.add(order+"-"+FeatureUtils.filterFieldNameForMongo(FeatureUtils.firstInitialLastName(author)),1.0)
}
class BagOfTitles(val entity:Entity,titleBag:Map[String,Double]=null) extends BagOfWordsVariable(Nil, titleBag) with EntityAttr
class Kind(val entity:Entity, kind:String) extends StringVariable(kind) with EntityAttr
class PromotedMention(val entity:Entity,id:String) extends StringVariable(id) with EntityAttr
//class AuthorList(val entity:Entity) extends SeqVariable[String] with EntityAttr
//author attributes
class BagOfTopics(val entity:Entity, topicBag:Map[String,Double]=null) extends BagOfWordsVariable(Nil, topicBag) with EntityAttr
class BagOfVenues(val entity:Entity, venues:Map[String,Double]=null) extends BagOfWordsVariable(Nil, venues) with EntityAttr
class BagOfCoAuthors(val entity:Entity,coAuthors:Map[String,Double]=null) extends BagOfWordsVariable(Nil, coAuthors) with EntityAttr
class BagOfEmails(val entity:Entity,keywords:Map[String,Double]=null) extends BagOfWordsVariable(Nil,keywords) with EntityAttr
class BagOfFirstNames(val entity:Entity,names:Map[String,Double]=null) extends BagOfWordsVariable(Nil,names) with EntityAttr{
  override def add(s:String,w:Double=1.0)(implicit d:DiffList) = super.add(FeatureUtils.normalizeName(s).toLowerCase,w)(d)
}
class BagOfMiddleNames(val entity:Entity,names:Map[String,Double]=null) extends BagOfWordsVariable(Nil,names) with EntityAttr{
  override def add(s:String,w:Double=1.0)(implicit d:DiffList) = super.add(FeatureUtils.normalizeName(s).toLowerCase,w)(d)
}
//misc attributes
class BagOfKeywords(val entity:Entity,keywords:Map[String,Double]=null) extends BagOfWordsVariable(Nil,keywords) with EntityAttr
/**Entity variables*/
/**An entity with the necessary variables/coordination to implement hierarchical coreference.*/
class TensorBagOfTopics(val entity:Entity) extends BagOfWordsTensorVariable with EntityAttr
class TensorBagOfVenues(val entity:Entity) extends BagOfWordsTensorVariable with EntityAttr
class TensorBagOfCoAuthors(val entity:Entity) extends BagOfWordsTensorVariable with EntityAttr
class TensorBagOfKeywords(val entity:Entity) extends BagOfWordsTensorVariable with EntityAttr

class PaperEntity(s:String="DEFAULT",isMention:Boolean=false) extends HierEntity(isMention) with HasCanopyAttributes[PaperEntity] with Prioritizable with BibEntity{
  var _id = java.util.UUID.randomUUID.toString+""
  override def id = _id
  canopyAttributes += new PaperTitleCanopy(this)
  protected def outer:PaperEntity = this
  attr += new Title(this,s)
  attr += new Year(this,-1)
  attr += new VenueName(this,"")
  attr += new BagOfTopics(this)
  attr += new BagOfAuthors(this)
  attr += new BagOfKeywords(this)
  attr += new BagOfVenues(this)
  attr += new BagOfTitles(this)
  attr += new PromotedMention(this, "NOT-SET")
  def title = attr[Title]
  def year = attr[Year]
  def venueName = attr[VenueName]
  def bagOfTopics = attr[BagOfTopics]
  def bagOfAuthors = attr[BagOfAuthors]
  def bagOfKeywords = attr[BagOfKeywords]
  def bagOfVenues = attr[BagOfVenues]
  def bagOfTitles = attr[BagOfTitles]
  def promotedMention = attr[PromotedMention]
  def string = title.toString
  val authors = new ArrayBuffer[AuthorEntity]{
    override def += (a:AuthorEntity) = {
      a.paper=outer
      a.paperMentionId=outer.id
      bagOfAuthors.addAuthor(a,size)(null)
      super.+=(a)
    }
  }
  def propagateAddBagsUp()(implicit d:DiffList):Unit = {throw new Exception("not implemented")}
  def propagateRemoveBagsUp()(implicit d:DiffList):Unit = {throw new Exception("not implemented")}
}
class AuthorEntity(f:String="DEFAULT",m:String="DEFAULT",l:String="DEFAULT", isMention:Boolean = false) extends HierEntity(isMention) with HasCanopyAttributes[AuthorEntity] with Prioritizable with BibEntity with HumanEditMention{
  //println("   creating author: f:"+f+" m: "+m+" l: "+l)
  var _id = java.util.UUID.randomUUID.toString+""
  override def id = _id
  priority = random.nextDouble
  canopyAttributes += new AuthorFLNameCanopy(this)
  def entity:HierEntity = this
  def addMoreCanopies:Unit = (fullName.firstName+" "+fullName.middleName+" "+fullName.lastName).replaceAll(" +"," ").trim.split(" ").toSet.foreach((s:String) => canopyAttributes += new SimpleStringCanopy(this,s))
  attr += new FullName(this,f,m,l)
  attr += new BagOfTopics(this)
  attr += new BagOfVenues(this)
  attr += new BagOfCoAuthors(this)
  attr += new BagOfKeywords(this)
  attr += new BagOfEmails(this)
  attr += new Year(this,-1)
  attr += new Title(this)
  def fullName = attr[FullName]
  def bagOfTopics = attr[BagOfTopics]
  def bagOfVenues = attr[BagOfVenues]
  def bagOfCoAuthors = attr[BagOfCoAuthors]
  def bagOfKeywords = attr[BagOfKeywords]
  def bagOfEmails = attr[BagOfEmails]
  def title = attr[Title]
  def string = f+" "+m+" "+l
  var paper:PaperEntity = null
  val bagOfFirstNames = new BagOfFirstNames(this)
  val bagOfMiddleNames = new BagOfMiddleNames(this)
  attr += bagOfFirstNames
  attr += bagOfMiddleNames
}

object Coref{
  lazy val ldaOpt = if(ldaFileOpt==None)None else Some(LDAUtils.loadLDAModelFromAlphaAndPhi(new File(ldaFileOpt.get)))
  var ldaFileOpt:Option[String] = None
  def main(argsIn:Array[String]) ={
    var args:Array[String]=new Array[String](0)
    if(argsIn.length>0 && argsIn.head.startsWith("--config")){
      val contents = scala.io.Source.fromFile(new File(argsIn.head.split("=")(1))).mkString
      args = contents.split("\\s+") ++ argsIn
    } else args=argsIn
    println("Args: "+args.length)
    for(arg <- args)
      println("  "+arg)
    object opts extends ExperimentOptions with AuthorModelOptions with PaperModelOptions{
      val advanceSeed = new CmdOption("advance-seed",0,"INT","Number of times to call random.nextInt to advance the seed.")
      //db options
      val server = new CmdOption("server","localhost","FILE","Location of Mongo server.")
      val port = new CmdOption("port","27017","FILE","Port of Mongo server.")
      val database = new CmdOption("database","rexa2-cubbies","FILE","Name of mongo database.")
      //db creation options
      val bibDirectory = new CmdOption("bibDir","/Users/mwick/data/thesis/all3/","FILE","Pointer to a directory containing .bib files.")
      val bibIdFilterListFile = new CmdOption("bib-filter-list-file","none","FILE","A file containing one id per line. Only bibtex files with an id in this list will be retained.")
      val bibUseKeysAsIds = new CmdOption("bib-use-keys-as-ids",false,"BOOLEAN","If true then use the citation key as the id, otherwise generate a random id.")
      val bibOptimizeForLargeFilesHack = new CmdOption("bib-optimize-large-hack",false,"BOOLEAN","If true then will split on @ and feed individual entires to the parallel bibtex loader.")
      val dblpLocation = new CmdOption("dblpFile","none","FILE","Pointer to a directory containing .bib files.")
      val rexaData = new CmdOption("rexaData","/Users/mwick/data/rexa/rexaAll/","FILE","Location of the labeled Rexa directory.")
      val rexaXMLDir = new CmdOption("rexaXMLDir","none","FILE","Location of the labeled Rexa xml directory.") ///Users/mwick/data/author-coref/from-aron/Training_data/xml/
      val aronData = new CmdOption("aronData","/data/thesis/rexa1/rexa_coref_datav0.5/","FILE","Location of Aron's labeled data")
      val aclAnthologyFile = new CmdOption("aclFile","none","FILE","Location of the \"acl-metadata.txt\" ACL Anthology data file. E.g., /iesl/canvas/soergel/aan/release/2011/acl-metadata.txt")
      val rexaFileList = new CmdOption("rexaFileList","none","FILE","Location of the file listing the locations of REXA \".crf.xml\" files to load.")
      val createDB = new CmdOption("create","true","FILE","Creates a new DB by destroying the old one.")
      val ldaModel = new CmdOption("ldaModel","lda-model.txt","FILE","Location of lda model")
      val saveDB = new CmdOption("save",true,"FILE","Creates a new DB by destroying the old one.")
      val inferenceType = new CmdOption("inference-type","default","FILE","Indicates the type of entity to perform coreference on, options are: paper, author, venue, trained-authors.")
      val trainingSamples = new CmdOption("training-samples",100000,"FILE","Number of samples to train")
      //val checkDBIntegrity = new CmdOption("check-integrity",false,"BOOL","If true then check the integrity of all entities in the DB")
      //inference options
      val numEpochs = new CmdOption("epochs",1,"FILE","Number of inference round-trips to DB.")
      val useParallelAuths = new CmdOption("use-pl-auth",true,"BOOL","True means use parallel author sampler, false uses normal author sampler.")
      val batchSize = new CmdOption("batchSize",10000,"FILE","Number of entities used to retrieve canopies from.")
      val stepMultiplierA = new CmdOption("a",0.0,"FILE","Runs for n^2 steps (n=number of mentions to do inference on.)")
      val stepMultiplierB = new CmdOption("b",0.0,"FILE","Runs for n steps (n=number of mentions to do inference on.)")
      val stepMultiplierC = new CmdOption("c",1000000.0,"FILE","Runs for c steps (c=constant)")
      val evaluateOnly = new CmdOption("evaluate","false","FILE","Loads labeled data, evaluates the accuracy of coreference, and exits.")
      val printProcessed = new CmdOption("print-entities","false","FILE","Prints all non-singleton entities then exits")
      val saveOptions = new CmdOption("optionsFile","options.log","FILE","Loads labeled data, evaluates the accuracy of coreference, and exits.")
      val copyTarget = new CmdOption("copy-target","copy-target","FILE","Copies bibtex to this target directory.")
      val exportAuthorIds = new CmdOption("export-authors","none","FILE","Exports authors into a file listing (entity_id mention_id) pairs.")
    }
    opts.parse(args)
    if(opts.copyTarget.wasInvoked){
      println("About to copy bibtex files")
      Utils.copySubset(new File(opts.bibDirectory.value),new File(opts.copyTarget.value),new File(opts.bibIdFilterListFile.value))
      println("Done copying, exiting.")
      System.exit(0)
    }
    for(i<-0 until opts.advanceSeed.value)random.nextInt
    if(opts.saveOptions.wasInvoked)opts.writeOptions(new File(opts.saveOptions.value))
    if(opts.ldaModel.wasInvoked)ldaFileOpt = Some(opts.ldaModel.value)
    if(opts.evaluateOnly.value.toBoolean){
      val tmpDB = new EpistemologicalDB(new AuthorCorefModel(),opts.server.value,opts.port.value.toInt,opts.database.value)
      tmpDB.loadAndEvaluateAuthors
      println("Done evaluating, exiting.")
      System.exit(0)
    }
    if(opts.exportAuthorIds.wasInvoked){
      val tmpDB = new EpistemologicalDB(new AuthorCorefModel(),opts.server.value,opts.port.value.toInt,opts.database.value)
      val es = tmpDB.authorColl.loadAll
      EntityUtils.prettyPrintAuthors(es)
      EntityUtils.export(new File(opts.exportAuthorIds.value),es)
      println("Done exporting, exiting.")
      System.exit(0)
    }
    if(opts.printProcessed.value.toBoolean){
      println("Print processed invoked.")
      val tmpDB = new EpistemologicalDB(new AuthorCorefModel(),opts.server.value,opts.port.value.toInt,opts.database.value)
      val entities = tmpDB.authorColl.loadProcessed(100).asInstanceOf[Seq[AuthorEntity]]
      EntityUtils.prettyPrintAuthors(entities)
      System.exit(0)
    }
    //Watch order here: first drop the collection, then create the epidb, then insert mentions. Required to make sure indexes remain intact.
    if(opts.createDB.value.toBoolean){
      println("Dropping database.")
      val mongoConn = new Mongo(opts.server.value,opts.port.value.toInt)
      val mongoDB = mongoConn.getDB(opts.database.value)
      mongoDB.getCollection("authors").drop
      mongoDB.getCollection("papers").drop
      mongoConn.close
      //mongoDB.getCollection("cooc").drop
    }
    println("server: "+opts.server.value+" port: "+opts.port.value.toInt+" database: "+opts.database.value)
    val authorCorefModel = new AuthorCorefModel(true)
    if(opts.entitySizeWeight.value != 0.0)authorCorefModel += new EntitySizePrior(opts.entitySizeWeight.value,opts.entitySizeExponent.value)
    if(opts.bagTopicsWeight.value != 0.0)authorCorefModel += new ChildParentCosineDistance[BagOfTopics](opts.bagTopicsWeight.value,opts.bagTopicsShift.value)
    if(opts.bagTopicsEntropy.value != 0.0)authorCorefModel += new EntropyBagOfWordsPriorWithStatistics[BagOfTopics](opts.bagTopicsEntropy.value)
    if(opts.bagTopicsPrior.value != 0.0)authorCorefModel += new BagOfWordsPriorWithStatistics[BagOfTopics](opts.bagTopicsPrior.value)
    if(opts.bagCoAuthorWeight.value != 0.0)authorCorefModel += new ChildParentCosineDistance[BagOfCoAuthors](opts.bagCoAuthorWeight.value,opts.bagCoAuthorShift.value)
    if(opts.bagCoAuthorEntropy.value != 0.0)authorCorefModel += new EntropyBagOfWordsPriorWithStatistics[BagOfCoAuthors](opts.bagCoAuthorEntropy.value)
    if(opts.bagCoAuthorPrior.value != 0.0)authorCorefModel += new BagOfWordsPriorWithStatistics[BagOfCoAuthors](opts.bagCoAuthorPrior.value)
    if(opts.bagVenuesWeight.value != 0.0)authorCorefModel += new ChildParentCosineDistance[BagOfVenues](opts.bagVenuesWeight.value,opts.bagVenuesShift.value)
    if(opts.bagVenuesEntropy.value != 0.0)authorCorefModel += new EntropyBagOfWordsPriorWithStatistics[BagOfVenues](opts.bagVenuesEntropy.value)
    if(opts.bagVenuesPrior.value != 0.0)authorCorefModel += new BagOfWordsPriorWithStatistics[BagOfVenues](opts.bagVenuesPrior.value)
    if(opts.bagKeywordsWeight != 0.0)authorCorefModel += new ChildParentCosineDistance[BagOfKeywords](opts.bagKeywordsWeight.value,opts.bagKeywordsShift.value)
    if(opts.bagKeywordsEntropy.value != 0.0)authorCorefModel += new EntropyBagOfWordsPriorWithStatistics[BagOfKeywords](opts.bagKeywordsEntropy.value)
    if(opts.bagKeywordsPrior.value != 0.0)authorCorefModel += new BagOfWordsPriorWithStatistics[BagOfKeywords](opts.bagKeywordsPrior.value)
    if(opts.entityExistencePenalty.value!=0.0 || opts.subEntityExistencePenalty.value!=0.0)authorCorefModel += new StructuralPriorsTemplate(opts.entityExistencePenalty.value, opts.subEntityExistencePenalty.value)
    val paperCorefModel = new PaperCorefModel
    if(opts.paperBagOfAuthorsWeight.value != 0.0)paperCorefModel += new ChildParentCosineDistance[BagOfAuthors](opts.paperBagOfAuthorsWeight.value,opts.paperBagOfAuthorsShift.value)
    if(opts.paperBagAuthorsEntropy.value != 0.0)paperCorefModel += new EntropyBagOfWordsPriorWithStatistics[BagOfAuthors](opts.paperBagAuthorsEntropy.value)
    if(opts.paperBagAuthorsPrior.value != 0.0)paperCorefModel += new BagOfWordsPriorWithStatistics[BagOfAuthors](opts.paperBagAuthorsPrior.value)
    if(opts.paperBagOfTitlesWeight.value != 0.0)paperCorefModel += new ChildParentCosineDistance[BagOfTitles](opts.paperBagOfTitlesWeight.value,opts.paperBagOfTitlesShift.value)
    if(opts.paperBagAuthorsEntropy.value != 0.0)paperCorefModel += new EntropyBagOfWordsPriorWithStatistics[BagOfTitles](opts.paperBagAuthorsEntropy.value)
    if(opts.paperBagAuthorsPrior.value != 0.0)paperCorefModel += new BagOfWordsPriorWithStatistics[BagOfTitles](opts.paperBagAuthorsPrior.value)
    if(opts.paperEntityExistencePenalty.value != 0.0 && opts.paperSubEntityExistencePenalty.value != 0.0)paperCorefModel += new StructuralPriorsTemplate(opts.paperEntityExistencePenalty.value,opts.paperSubEntityExistencePenalty.value)

    val epiDB = new EpistemologicalDB(authorCorefModel,opts.server.value,opts.port.value.toInt,opts.database.value,opts.useParallelAuths.value)
    println("About to add data.")
    if(opts.bibDirectory.value.toLowerCase != "none"){
      val bibDirFile = new File(opts.bibDirectory.value)
      if(bibDirFile.isDirectory){
        for(file <- bibDirFile.listFiles.par)
          epiDB.insertMentionsFromBibFile(file,opts.bibUseKeysAsIds.value)
      } else epiDB.insertMentionsFromBibFile(new File(opts.bibDirectory.value),opts.bibUseKeysAsIds.value)
      //epiDB.insertMentionsFromBibDirMultiThreaded(new File(opts.bibDirectory.value))
    //epiDB.insertMentionsFromBibDir(new File(opts.bibDirectory.value))
    }
    if(opts.rexaData.value.toLowerCase != "none"){
      println("Loading labeled data from: " + opts.rexaData.value)
      epiDB.insertLabeledRexaMentions(new File(opts.rexaData.value))
    }
    if(opts.rexaXMLDir.value.toLowerCase != "none"){
      println("Loading Rexa XML data from: "+opts.rexaXMLDir.value)
      val papers = RexaLabeledLoader.loadFromXMLDir(new File(opts.rexaXMLDir.value))
      epiDB.add(papers)
    }
    if(opts.dblpLocation.value.toLowerCase != "none"){
      println("Loading DBLP data from: " + opts.dblpLocation.value)
      epiDB.insertMentionsFromDBLP(opts.dblpLocation.value)
      println("done.")
    }

    if(opts.aclAnthologyFile.value.toLowerCase != "none"){
      val papers = AclAnthologyReader.loadAnnFile(new File(opts.aclAnthologyFile.value))
      epiDB.add(papers)
    }
    if(opts.rexaFileList.value.toLowerCase != "none"){
      val papers = RexaCitationLoader.loadFromList(new File(opts.rexaFileList.value))
      epiDB.add(papers)
    }
    if(opts.inferenceType.value=="papers")epiDB.processAllPapers(opts.stepMultiplierA.value.toDouble,opts.stepMultiplierB.value.toDouble,opts.stepMultiplierC.value.toDouble,true)
    else if(opts.inferenceType.value=="authors"){
      epiDB.processAllAuthors(opts.stepMultiplierA.value.toDouble,opts.stepMultiplierB.value.toDouble,opts.stepMultiplierC.value.toDouble,opts.saveDB.value)
    }
    else if(opts.inferenceType.value=="default"){
      epiDB.inferenceSweep(
        opts.numEpochs.value.toInt,
        opts.batchSize.value.toInt,
        opts.stepMultiplierA.value.toDouble,opts.stepMultiplierB.value.toDouble,opts.stepMultiplierC.value.toDouble,
        opts.saveDB.value
      )
    }
    else if(opts.inferenceType.value=="trained-authors"){
      val initGroundTruth=false
      if(opts.trainingSamples.value>0){
        if(opts.bagTopicsWeight.value != 0.0)authorCorefModel += new WeightedChildParentCosineDistance[BagOfTopics]("topics",opts.bagTopicsWeight.value,opts.bagTopicsShift.value)
        if(opts.bagCoAuthorWeight.value != 0.0)authorCorefModel += new WeightedChildParentCosineDistance[BagOfCoAuthors]("coauthors",opts.bagCoAuthorWeight.value,opts.bagCoAuthorShift.value)
        if(opts.bagVenuesWeight.value != 0.0)authorCorefModel += new WeightedChildParentCosineDistance[BagOfVenues]("venues",opts.bagVenuesWeight.value,opts.bagVenuesShift.value)
        if(opts.bagKeywordsWeight.value != 0.0)authorCorefModel += new WeightedChildParentCosineDistance[BagOfKeywords]("keywords",opts.bagKeywordsWeight.value,opts.bagKeywordsShift.value)
      }
      println("Num templates: "+authorCorefModel.families.size)
      //paralellizing learning will require either having a thread-safe growable domain or just having separate domains for each thread.
      val labeled = random.shuffle(epiDB.authorColl.loadLabeled).filter(_.groundTruth != None)
      val canopies = new HashMap[String,ArrayBuffer[AuthorEntity]]
      for(author <- labeled)canopies.getOrElseUpdate(author.canopyAttributes.head.canopyName,new ArrayBuffer[AuthorEntity]) += author
      val trainingSet = new ArrayBuffer[AuthorEntity]
      val testingSet = new ArrayBuffer[AuthorEntity]
      for((canopyName,canopy) <- canopies){
        if(trainingSet.size<testingSet.size){
          if(initGroundTruth)trainingSet ++= EntityUtils.collapseOnTruth(canopy)
          else trainingSet ++= canopy
        } else testingSet ++= canopy
      }
      println("Split data into "+trainingSet.size + " training authors and "+testingSet.size+" testing authors.")
      println("Training set accuracy (initial state)")
      Evaluator.eval(trainingSet)
      val trainingModel = new TrainingModel
      val sampler = new AuthorSampler(authorCorefModel){temperature=0.001;override def objective=trainingModel}
      sampler.setEntities(trainingSet)
      val averager = new WeightsAveraging(new MIRA)
      val trainer = new SampleRankTrainer(sampler, averager)
      //val trainer = new SampleRankTrainer(sampler, new AROW(authorCorefModel))
      //val trainer = new SampleRankTrainer(sampler, new MIRA)
      Console.println ("Beginning inference and learning")
      trainer.processContext(null, opts.trainingSamples.value) // 3000
      averager.setWeightsToAverage(authorCorefModel.weightsTensor)
      //val pSampler = new ParallelAuthorSampler(authorCorefModel){temperature = 0.001}
      sampler.setEntities(testingSet)
      println("Beginning testing")
      sampler.timeAndProcess(opts.stepMultiplierC.value.toInt)
    }
    else throw new Exception("Error inference-type: "+opts.inferenceType.value + " not recognized. Options are: {papers,authors,default}")
  }
}

abstract class MongoDBBibEntityCollection[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable with BibEntity,C<:BibEntityCubbie[E]](name:String, mongoDB:DB) extends MongoDBEntityCollection[E,C](name,mongoDB){
  override val entityCubbieColl = new MongoCubbieCollection(entityColl,() => newEntityCubbie,(a:C) => Seq(Seq(a.canopies),Seq(a.inferencePriority),Seq(a.parentRef),Seq(a.bagOfTruths),Seq(a.pid))) with LazyCubbieConverter[C]
}

class EpistemologicalDB(authorCorefModel:AuthorCorefModel,mongoServer:String="localhost",mongoPort:Int=27017,mongoDBName:String="rexa2-cubbie",usePAuths:Boolean=true) extends MongoBibDatabase(mongoServer,mongoPort,mongoDBName){
  //val authorPredictor = new AuthorSampler(authorCorefModel){temperature = 0.001}
  val authorPredictor = if(usePAuths)new ParallelAuthorSampler(authorCorefModel){temperature = 0.001} else new AuthorSampler(authorCorefModel){temperature = 0.001}
  val paperPredictor = new PaperSampler(new PaperCorefModel){temperature = 0.001}
  def drop:Unit = {
    //authorColl.remove(_)
    //paperColl.remove(_)
    authorColl.drop
    paperColl.drop
  }
  protected def infer[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable with BibEntity,C<:BibEntityCubbie[E]](entityCollection:DBEntityCollection[E,C],predictor:HierCorefSampler[E],k:Int,inferenceSteps:Int=>Int,save:Boolean=true) ={
    println("About to load an inference batch centered around "+k + " entities.")
    val entities = entityCollection.nextBatch(k)
    //val entities = random.shuffle(entityCollection.loadLabeledAndCanopies) //entityCollection.nextBatch(k)
    EntityUtils.checkIntegrity(entities)
    Evaluator.eval(entities)
    var timer = System.currentTimeMillis
    val numSteps = inferenceSteps(entities.size)
    predictor.setEntities(entities)
    predictor.timeAndProcess(numSteps)
    EntityUtils.printClusteringStats(predictor.getEntities.asInstanceOf[Seq[AuthorEntity]])
    //EntityUtils.printAuthorsForAnalysis(predictor.getEntities.asInstanceOf[Seq[AuthorEntity]])
    //println("====Printing authors====")
    //EntityUtils.prettyPrintAuthors(predictor.getEntities.asInstanceOf[Seq[AuthorEntity]])

    //predictor.getEntities.asInstanceOf[Seq[AuthorEntity]].foreach((e:AuthorEntity) => {EntityUtils.prettyPrintAuthor(e);println})
    println(numSteps + " of inference took " + ((System.currentTimeMillis-timer)/1000L) + " seconds.")
    if(save)entityCollection.store(predictor.getEntities ++ predictor.getDeletedEntities.map(_.asInstanceOf[E]))
  }
  def processAllAuthors(a:Double,b:Double,c:Double,save:Boolean):Unit ={
    println("Processing all authors.")
    val entities = random.shuffle(authorColl.loadAll)
    val numEntities = entities.size.toDouble
    val numSteps = (numEntities*numEntities*a + numEntities*b + c).toInt
    EntityUtils.checkIntegrity(entities)
    Evaluator.eval(entities)
    var timer = System.currentTimeMillis
    //val numSteps = inferenceSteps(entities.size)
    authorPredictor.setEntities(entities)
    authorPredictor.timeAndProcess(numSteps)
    Evaluator.eval(entities)
    println(numSteps + " of inference took " + ((System.currentTimeMillis-timer)/1000L) + " seconds.")
    if(save)authorColl.store(authorPredictor.getEntities ++ authorPredictor.getDeletedEntities.map(_.asInstanceOf[AuthorEntity]))
  }
  def processAllPapers(a:Double,b:Double,c:Double,hashInitialize:Boolean=false):Unit ={
    println("Processing all papers.")
    var entities:Seq[PaperEntity] = random.shuffle(paperColl.loadAll)
    if(hashInitialize){
      entities = EntityUtils.collapseOnTitleHash(entities)
    }
    val numEntities = entities.size.toDouble
    val numSteps = (numEntities*numEntities*a + numEntities*b + c).toInt
    EntityUtils.checkIntegrity(entities)
    Evaluator.eval(entities)
    var timer = System.currentTimeMillis
    //val numSteps = inferenceSteps(entities.size)
    paperPredictor.setEntities(entities)
    paperPredictor.timeAndProcess(numSteps)
    println(numSteps + " of inference took " + ((System.currentTimeMillis-timer)/1000L) + " seconds.")
    println("Promoting mentions for paper entities...")
    val processedPapers = paperPredictor.getEntities ++ paperPredictor.getDeletedEntities.map(_.asInstanceOf[PaperEntity])
    for(paper <- processedPapers)paperPredictor.chooseCanonicalMention(paper)(null)
    //println("Updating database...")
    //if(save)paperColl.store(processedPapers)
  }

  def loadAndEvaluateAuthors:Unit ={
    val labeledAuthors = authorColl.loadLabeled
    Evaluator.eval(labeledAuthors)
  }

  /*
  //assumes everything is singletons
  protected def trainTestAuthors(pctTrain:Double, trainingEpochs:Int,trainingSteps:Int=>Int, testingSteps:Int=>Int):Unit ={
    val trainingSet = new ArrayBuffer[AuthorEntity]
    val testingSet = new ArrayBuffer[AuthorEntity]
    var entities = authorColl.nextBatch(10000)
    for(e<-entities.filter(_.groundTruth!=None)){
      if(e.groundTruth.get.startsWith("rexaTrain"))trainingSet += e
      else if(e.groundTruth.get.startsWith("rexaTest"))testingSet += e
    }
    println("Num mentions: "+entities.size)
    println("Training set size: "+trainingSet.size)
    println("Testing set.size: "+testingSet.size)
    EntityUtils.printAuthors(EntityUtils.makeTruth(trainingSet))
    println("\nTRAINING...")
    for(i <- 0 until trainingEpochs){
      println("EPOCH "+i)
      //val ts = trainingSet
      //val ts = if((i+1) % 3 != 0) EntityUtils.makeSingletons(trainingSet) else EntityUtils.makeTruth(trainingSet)
      val ts = EntityUtils.makeTruth(trainingSet)
      //val ts = EntityUtils.makeSingletons(trainingSet)
      Evaluator.eval(ts)
      authorTrainer.setEntities(ts)
      authorTrainer.process(10000)
      //authorTrainer.process(trainingSteps(ts.size)/trainingEpochs)
      Evaluator.eval(authorTrainer.getEntities)
    }


    println("\nTESTING...")
    authorPredictor.setEntities(testingSet)
    authorPredictor.process(testingSteps(testingSet.size))
    EntityUtils.printAuthors(authorPredictor.getEntities)
    Evaluator.eval(authorPredictor.getEntities)
  }*/
  def inferenceSweep(numEpochs:Int,k:Int,a:Double,b:Double,c:Double, save:Boolean=true) ={
    def calculateSteps(numEntities:Int) = (numEntities.toDouble*numEntities.toDouble*a + numEntities.toDouble*b + c).toInt
    for(i<-0 until numEpochs){
      println("INFERENCE ROUND "+i)
      /*
      println("Paper coreference")
      //infer[PaperEntity,PaperCubbie](paperColl,paperPredictor,k,calculateSteps(_))
      val papers = paperColl.nextBatch(k)
      paperPredictor.setEntities(papers)
      paperPredictor.process(calculateSteps(papers.size))
      val processedPapers = paperPredictor.getEntities ++ paperPredictor.getDeletedEntities.map(_.asInstanceOf[PaperEntity])
      for(paper <- processedPapers)paperPredictor.chooseCanonicalMention(paper)(null)
      paperColl.store(processedPapers)
      EntityUtils.printPapers(paperPredictor.getEntities)
      */
      println("Author coreference")
      //trainTestAuthors(0.6,10,calculateSteps(_),calculateSteps(_))
      infer[AuthorEntity,AuthorCubbie](authorColl,authorPredictor,k,calculateSteps(_),save)
      //EntityUtils.printAuthors(authorPredictor.getEntities)
      Evaluator.eval(authorPredictor.getEntities)
    }
  }
}

/**Models*/
class TrainingModel extends CombinedModel {
  /*
  this += new ChildParentTemplateWithStatistics[BagOfTruths]{
    override def unroll2(childBow:BagOfTruths) = Nil
    override def unroll3(childBow:BagOfTruths) = Nil
    def score(s:Statistics):Double ={
      val childBow  =s._2
      val parentBow =s._3
      val result = childBow.cosineSimilarity(parentBow,childBow)
      result
    }
  }
  */
  this += new TupleTemplateWithStatistics2[BagOfTruths,IsEntity]{
    val precisionDominated=0.95
    def unroll1(bot:BagOfTruths) =if(bot.entity.isRoot)Factor(bot,bot.entity.attr[IsEntity]) else Nil
    def unroll2(isEntity:IsEntity) = if(isEntity.entity.isRoot)Factor(isEntity.entity.attr[BagOfTruths],isEntity) else Nil
    override def score(bag:BagOfTruths#Value, isEntity:IsEntity#Value):Double ={
      var result = 0.0
      //val bag = s._1
      val bagSeq = bag.iterator.toSeq
      var i=0;var j=0;
      var tp = 0.0
      var fp = 0.0
      while(i<bagSeq.size){
        val e:AuthorEntity=null
        val (labeli,weighti) = bagSeq(i)
        j = i
        while(j<bagSeq.size){
          val (labelj,weightj) = bagSeq(j)
          if(labeli==labelj)
            tp += (weighti*(weighti-1))/2.0
          else
            fp += weighti*weightj
          j += 1
        }
        i += 1
      }
      val normalizer = tp+fp
      result = tp - fp
      //println("  bag: "+bag)
      //println("    tp: "+tp)
      //println("    fp: "+fp)
      //println("    result: "+result)
      // result = tp*(1.0 - precisionDominated) - fp*(precisionDominated)
      //if(fp==0) result = tp else result = -fp

      //println("  normalized: "+result/normalizer)
      result
    }
  }
}
class PaperCorefModel extends CombinedModel {
  var bagOfAuthorsShift = -1.0
  var bagOfAuthorsWeight= 2.0
  /*
  this += new ChildParentTemplateWithStatistics[Title]{
    def score(er:EntityRef#Value, childTitle:Title#Value, parentTitle: Title#Value):Double ={
      if(childTitle != parentTitle) -16.0 else 0.0
    }
  }
  */
  /*
  this += new ChildParentTemplateWithStatistics[BagOfAuthors] {
    override def unroll2(childBow:BagOfAuthors) = Nil
    override def unroll3(childBow:BagOfAuthors) = Nil
    def score(s:Statistics): Double = {
      val childBow = s._2
      val parentBow = s._3
      var result = childBow.cosineSimilarity(parentBow,childBow)
      (result+bagOfAuthorsShift)*bagOfAuthorsWeight
    }
  }
  */
  //this += new StructuralPriorsTemplate(8.0,0.25)
}



class EntityNameTemplate[B<:BagOfWordsVariable with EntityAttr](val firstLetterWeight:Double=4.0, val fullNameWeight:Double=4.0,val weight:Double=64,val saturation:Double=128.0)(implicit m:Manifest[B]) extends TupleTemplateWithStatistics3[EntityExists,IsEntity,B] with DebugableTemplate{
  val name = "EntityNameTemplate(flWeight="+firstLetterWeight+", fnWeight="+fullNameWeight+", weight="+weight+" sat="+saturation+")"
  println("EntityNameTemplate("+weight+")")
  def unroll1(exists:EntityExists) = Factor(exists,exists.entity.attr[IsEntity],exists.entity.attr[B])
  def unroll2(isEntity:IsEntity) = Factor(isEntity.entity.attr[EntityExists],isEntity,isEntity.entity.attr[B])
  def unroll3(bag:B) = Factor(bag.entity.attr[EntityExists],bag.entity.attr[IsEntity],bag)//throw new Exception("An entitie's status as a mention should never change.")
  def score(exists:EntityExists#Value, isEntity:IsEntity#Value, bag:B#Value): Double ={
    var result = 0.0
    val bagSeq = bag.iterator.filter(_._1.length>0).toSeq
    var firstLetterMismatches = 0
    var nameMismatches = 0
    var i=0;var j=0;
    while(i<bagSeq.size){
      val (wordi,weighti) = bagSeq(i)
      j=i+1
      while(j<bagSeq.size){
        val (wordj,weightj) = bagSeq(j)
        if(wordi.charAt(0) != wordj.charAt(0))firstLetterMismatches += 1 //weighti*weightj
        else if(FeatureUtils.isInitial(wordi) && FeatureUtils.isInitial(wordj) && wordi != wordj)firstLetterMismatches += 1
        if(wordi.length>1 && wordj.length>1 && !FeatureUtils.isInitial(wordi) && !FeatureUtils.isInitial(wordj))nameMismatches += wordi.editDistance(wordj)
        j += 1
      }
      i += 1
    }
    result -= scala.math.min(saturation,firstLetterMismatches*firstLetterWeight)
    result -= scala.math.min(saturation,nameMismatches*fullNameWeight)
    result = result*weight
    if(_debug)println("  "+debug(result))
    result
  }
}


class AuthorCorefModel(includeDefaultTemplates:Boolean=true) extends CombinedModel{
  /*
    this += new ChildParentTemplateWithStatistics[FullName] {
    def score(s:Statistics): Double = {
      var result = 0.0
      val childName = s._2
      val parentName = s._3
      val childFirst = FeatureUtils.normalizeName(childName(0)).toLowerCase
      val childMiddle = FeatureUtils.normalizeName(childName(1)).toLowerCase
      val childLast = FeatureUtils.normalizeName(childName(2)).toLowerCase
      val parentFirst = FeatureUtils.normalizeName(parentName(0)).toLowerCase
      val parentMiddle = FeatureUtils.normalizeName(parentName(1)).toLowerCase
      val parentLast = FeatureUtils.normalizeName(parentName(2)).toLowerCase
      if(childLast != parentLast)result -= 8
      if(initialsMisMatch(childFirst,parentFirst))result -= 8
      if(initialsMisMatch(childMiddle,parentMiddle))result -= 8
      if(nameMisMatch(childFirst,parentFirst))result -= 8
      if(nameMisMatch(childMiddle,parentMiddle))result -= 2
      if(parentMiddle.length > childMiddle.length)result += 0.5
      if(parentFirst.length > childFirst.length)result += 0.5
      if((childMiddle.length==0 && parentMiddle.length>0) || (parentMiddle.length==0 && childMiddle.length>0))result -= 0.25
      result
    }
    def initialsMisMatch(c:String,p:String):Boolean = (c!=null && p!=null && c.length>0 && p.length>0 && c.charAt(0)!=p.charAt(0))
    def nameMisMatch(c:String,p:String):Boolean = (c!=null && p!=null && !FeatureUtils.isInitial(c) && !FeatureUtils.isInitial(p) && c != p)
  }
  */
  val saturation = 128
  if(includeDefaultTemplates)this += new TupleTemplateWithStatistics1[BagOfFirstNames]{
    def score(bag:BagOfFirstNames#Value):Double ={
      var result = 0.0
      //val bag = s._1
      //val bagSeq = bag.iterator.toSeq
      val bagSeq = bag.iterator.filter(_._1.length>0).toSeq
      var firstLetterMismatches = 0
      var nameMismatches = 0
      var i=0;var j=0;
      while(i<bagSeq.size){
        val (wordi,weighti) = bagSeq(i)
        j=i+1
        while(j<bagSeq.size){
          val (wordj,weightj) = bagSeq(j)
          if(wordi.charAt(0) != wordj.charAt(0))firstLetterMismatches += 1 //weighti*weightj
          else if(FeatureUtils.isInitial(wordi) && FeatureUtils.isInitial(wordj) && wordi != wordj)firstLetterMismatches += 1
          if(wordi.length>1 && wordj.length>1 && !FeatureUtils.isInitial(wordi) && !FeatureUtils.isInitial(wordj))nameMismatches += wordi.editDistance(wordj)
          j += 1
        }
        i += 1
      }
      result -= scala.math.min(saturation,firstLetterMismatches*firstLetterMismatches*4)
      result -= scala.math.min(saturation,nameMismatches*nameMismatches*4)
      /*
      val bag = s._1
      var fullNameCount = 0.0
      var initialCount = 0.0
      var result = 0.0
      for((k,v) <- bag.iterator)if(k.length>1)fullNameCount+=1 else if(k.length==1)initialCount+=1
      result -= (initialCount*initialCount -1)*4
      result -= (fullNameCount*fullNameCount -1)*4
      */
      result*8.0*8.0
    }
  }
  if(includeDefaultTemplates)this += new TupleTemplateWithStatistics1[BagOfMiddleNames]{
    def score(bag:BagOfMiddleNames#Value):Double ={
      var result = 0.0
      //val bag = s._1
      val bagSeq = bag.iterator.filter(_._1.length>0).toSeq
      //val bagSeq = bag.iterator.toSeq
      var firstLetterMismatches = 0
      var nameMismatches = 0
      var i=0;var j=0;
      while(i<bagSeq.size){
        val (wordi,weighti) = bagSeq(i)
        j=i+1
        while(j<bagSeq.size){
          val (wordj,weightj) = bagSeq(j)
          if(wordi.charAt(0) != wordj.charAt(0))firstLetterMismatches += 1 //weighti*weightj
          else if(FeatureUtils.isInitial(wordi) && FeatureUtils.isInitial(wordj) && wordi != wordj)firstLetterMismatches += 1
          if(wordi.length>1 && wordj.length>1 && !FeatureUtils.isInitial(wordi) && !FeatureUtils.isInitial(wordj))nameMismatches += wordi.editDistance(wordj)
          j += 1
        }
        i += 1
      }
      result -= scala.math.min(saturation,firstLetterMismatches*firstLetterMismatches*4)
      result -= scala.math.min(saturation,nameMismatches*nameMismatches*4)
      result*8.0*8.0
    }
  }
}

class AuthorSampler(model:Model) extends BibSampler[AuthorEntity](model){
  var settingsSamplerCount=0
  def newEntity = new AuthorEntity
  def sampleAttributes(author:AuthorEntity)(implicit d:DiffList) = {
    if(author.childEntities.size==0){
      EntityUtils.printAuthors(Seq(author))
      println("observeD? "+author.isObserved)
      println("entity? "+author.isRoot)
      println("exists? "+author.isConnected)
      throw new Exception("ERROR AUTHOR HAS NO CHILDREN")
    }
    val representative = author.childEntities.value.sampleUniformly(random)
    author.attr[FullName].setFullName(representative.attr[FullName])
    //author.attr[Dirty].reset
    if(author.attr[Dirty].value>0)author.attr[Dirty].--()(d)
    if(author.parentEntity != null)author.parentEntity.attr[Dirty].++()(d)
  }
  protected def createAttributesForMergeUp(e1:AuthorEntity,e2:AuthorEntity,parent:AuthorEntity)(implicit d:DiffList):Unit ={
    if(e1.attr[FullName].middleName.length>0)
      parent.attr[FullName].setFullName(e1.attr[FullName])
    else
      parent.attr[FullName].setFullName(e2.attr[FullName])
    EntityUtils.createBagsForMergeUp(e1,e2,parent)(d)
  }
  override def mergeLeft(left:AuthorEntity,right:AuthorEntity)(implicit d:DiffList):Unit ={
    val oldParent = right.parentEntity
    right.setParentEntity(left)(d)
    if(right.attr[FullName].middleName.size > left.attr[FullName].middleName.size)
      left.attr[FullName].setMiddle(left.attr[FullName].middleName)
    propagateBagUp(right)(d)
    propagateRemoveBag(right,oldParent)(d)
    structurePreservationForEntityThatLostChild(oldParent)(d)
  }
  def proposeMergeIfValid(entity1:AuthorEntity,entity2:AuthorEntity,changes:ArrayBuffer[(DiffList)=>Unit]):Unit ={
    if (entity1.entityRoot.id != entity2.entityRoot.id){ //sampled nodes refer to different entities
      if(!isMention(entity1))
        changes += {(d:DiffList) => mergeLeft(entity1,entity2)(d)} //what if entity2 is a mention?
      else if(!isMention(entity2))
        changes += {(d:DiffList) => mergeLeft(entity2,entity1)(d)}
    }
  }

  override def proposals(c:Null):Seq[Proposal] ={
    val result = super.proposals(c)
    settingsSamplerCount += 1
    if(totalTime==0L){totalTime = System.currentTimeMillis}
    /*
    if(printInfo){
      if(settingsSamplerCount % 1000==0)
        print(".")
      if(settingsSamplerCount % 1000==0){
        var sampsPerSec = -1
        val elapsed = ((System.currentTimeMillis - totalTime)/1000L).toInt
        if(elapsed != 0)sampsPerSec = settingsSamplerCount/elapsed
        println(settingsSamplerCount+" samps per sec: "+sampsPerSec+" elapsed: "+elapsed)
        Evaluator.eval(getEntities)
        //println("Proposals")
        //for(p <- result)
        //  println("   obj:"+p.objectiveScore+"  mod:"+p.modelScore)
      }
    }
    */
    result
  }

  override def settings(c:Null) : SettingIterator = new SettingIterator {

    val changes = new scala.collection.mutable.ArrayBuffer[(DiffList)=>Unit]
    val (entityS1,entityS2) = nextEntityPair
    val entity1 = entityS1.getAncestor(random.nextInt(entityS1.depth+1)).asInstanceOf[AuthorEntity]
    val entity2 = entityS2.getAncestor(random.nextInt(entityS2.depth+1)).asInstanceOf[AuthorEntity]
    if (entity1.entityRoot.id != entity2.entityRoot.id) { //sampled nodes refer to different entities
//      println("DIFFERENT ENTITIES")
      /*
      if(!isMention(entity1)){
        changes += {(d:DiffList) => mergeLeft(entity1,entity2)(d)} //what if entity2 is a mention?
        if(entity1.id != entity1.entityRoot.id) //avoid adding the same jump to the list twice
          changes += {(d:DiffList) => mergeLeft(entity1.entityRoot.asInstanceOf[T],entity2)(d)} //unfortunately casting is necessary unless we want to type entityRef/parentEntity/childEntities
      }
      */
      var e1 = entityS1
      var e2 = entity2//entityS2.getAncestor(random.nextInt(entityS2.depth+1)).asInstanceOf[AuthorEntity]
      while(e1 != null){
        proposeMergeIfValid(e1,e2,changes)
        //if(!e2.entityRoot eq e2)proposeMergeIfValid(e1,e2.entityRoot.asInstanceOf[AuthorEntity],changes)
        e1 = e1.parentEntity.asInstanceOf[AuthorEntity]
      }
//      println("   #proposed: "+changes.size)
      //proposeMergeIfValid(entity1,entity2,changes)
      //changes += {(d:DiffList) => mergeUp(entity1.entityRoot.asInstanceOf[AuthorEntity],entity2.entityRoot.asInstanceOf[AuthorEntity])(d)}
      if(entity1.parentEntity==null && entity2.parentEntity==null)
        changes += {(d:DiffList) => mergeUp(entity1,entity2)(d)}
      if(changes.size==0){
        val r2 = entity2.entityRoot.asInstanceOf[AuthorEntity]
        if(!isMention(r2)){
          proposeMergeIfValid(r2,entity1,changes)
        } else{
          val r1 = entity1.entityRoot.asInstanceOf[AuthorEntity]
          if(!isMention(r1)){
          proposeMergeIfValid(r1,entity2,changes)
          }else changes += {(d:DiffList) => mergeUp(entity1.entityRoot.asInstanceOf[AuthorEntity],entity2.entityRoot.asInstanceOf[AuthorEntity])(d)}

        }
        //println("no proposal: "+entity1.isMention+" "+entity2.isMention)
        //EntityUtils.prettyPrintAuthor(entity1.asInstanceOf())
      }
//      println("   #proposed: "+changes.size)
    } else { //sampled nodes refer to same entity
//      println("SAME ENTITY")
      changes += {(d:DiffList) => splitRight(entity1,entity2)(d)}
      changes += {(d:DiffList) => splitRight(entity2,entity1)(d)}
      if(entity1.parentEntity != null && !entity1.isObserved)
        changes += {(d:DiffList) => {collapse(entity1)(d)}}
//      println("   #proposed: "+changes.size)
    }
    if(entity1.dirty.value>0)changes += {(d:DiffList) => sampleAttributes(entity1)(d)}
    if(entity1.entityRoot.id != entity1.id && entity1.entityRoot.attr[Dirty].value>0)changes += {(d:DiffList) => sampleAttributes(entity1.entityRoot.asInstanceOf[AuthorEntity])(d)}

    changes += {(d:DiffList) => {}} //give the sampler an option to reject all other proposals
//    println("CHANGES SIZE: "+changes.size)
//    System.out.flush()
    var i = 0
    def hasNext = i < changes.length
    def next(d:DiffList) = {val d = newDiffList; changes(i).apply(d); i += 1; d }
    def reset = i = 0
  }
  override def proposalHook(proposal:Proposal) = {
    super.proposalHook(proposal)
    /*
    if(printInfo){
      if(proposalCount % printUpdateInterval==0){
        Evaluator.eval(getEntities)
      }
    }
    */
  }
}
class PaperSampler(model:Model) extends BibSampler[PaperEntity](model){
  def newEntity = new PaperEntity
  def chooseCanonicalMention(paper:PaperEntity)(implicit d:DiffList):Unit ={
    var canonical:PaperEntity = null
    for(c<- paper.descendantsOfClass[PaperEntity]){
      if(c.isObserved && (canonical==null || c.id.hashCode < canonical.id.hashCode))
        canonical = c
    }
    if(canonical==null)canonical=paper
    if(paper.isEntity.booleanValue)paper.promotedMention.set(canonical.id.toString) else paper.promotedMention.set(null.asInstanceOf[String])
  }
  def sampleAttributes(author:PaperEntity)(implicit d:DiffList) = {
    val representative = author.childEntities.value.sampleUniformly(random)
    author.attr[Title].set(representative.attr[Title].value)
    author.attr[Dirty].reset
    if(author.parentEntity != null)author.parentEntity.attr[Dirty].++()(d)
  }
  protected def createAttributesForMergeUp(e1:PaperEntity,e2:PaperEntity,parent:PaperEntity)(implicit d:DiffList):Unit ={
    parent.attr[Title].set(e1.title.value)
    parent.attr[BagOfAuthors].add(e1.attr[BagOfAuthors].value)(d)
    parent.attr[BagOfAuthors].add(e2.attr[BagOfAuthors].value)(d)
    parent.attr[BagOfVenues].add(e1.attr[BagOfVenues].value)(d)
    parent.attr[BagOfVenues].add(e2.attr[BagOfVenues].value)(d)
    parent.attr[BagOfKeywords].add(e1.attr[BagOfKeywords].value)(d)
    parent.attr[BagOfKeywords].add(e2.attr[BagOfKeywords].value)(d)
  }
}

trait CanopyStatistics[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable]{
  protected var _sampled:String = null
  var canopies = new HashMap[String,ArrayBuffer[E]]
  var canopyNames = new Array[String](1)
  var countsAccept = new HashMap[String,Double]
  var countsSamples = new HashMap[String,Double]
  var lastAccept = new HashMap[String,Double]
  def apply(canopyName:String):ArrayBuffer[E] = canopies(canopyName)
  def lastSampledName:String = _sampled
  def reset(cs:HashMap[String,ArrayBuffer[E]]=new HashMap[String,ArrayBuffer[E]]):Unit ={
    canopies = cs
    countsAccept = new HashMap[String,Double]
    countsSamples = new HashMap[String,Double]
    lastAccept = new HashMap[String,Double]
    canopyNames = new Array[String](canopies.size)
    var i=0
    for(canopyName <- cs.keys){
      canopyNames(i)=canopyName
      i+=1
    }
    _sampled = null
  }
  def addNewOnly(cs:HashMap[String,ArrayBuffer[E]]):Unit ={
    canopies = cs
    var i=0
    canopyNames = new Array[String](canopies.size)
    for(canopyName <- cs.keys){
      canopyNames(i)=canopyName
      i+=1
    }
  }
  def value(s:String) = (countsAccept.getOrElse(s,0.0) + ((random.nextDouble-0.5)/100.0+0.5))/(countsSamples.getOrElse(s,0.0)+1.0)
  def accept = {
    countsAccept(_sampled)=countsAccept.getOrElse(_sampled,0.0) + 1.0
    //lastAccept(_sampled) = countsSample.getOrElse(_sampled,0.0)+1 //countsSample can also be thought of as the per-canopy time scale
  }
  def sampled = countsSamples(_sampled) = countsSamples.getOrElse(_sampled,0.0) + 1.0
  def nextCanopy:String
  def print(n:Int=25):Unit={}
}
class ApproxMaxCanopySampling[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable](val maxCandidateSize:Int=20) extends CanopyStatistics[E]{
  protected var _max:String = null
  protected def _maxValue:Double = if(_max==null) -1.0 else value(_max)
  protected var candidates = new Array[String](candidateSize)
  protected var candidatesSet = new HashSet[String]
  protected var mentions:Seq[E] = null
  override def reset(cs:HashMap[String,ArrayBuffer[E]] = new HashMap[String,ArrayBuffer[E]]):Unit ={
    super.reset(cs)
    mentions = cs.flatMap(_._2).filter(_.isObserved).toSeq
    candidates = new Array[String](candidateSize)
    candidatesSet = new HashSet[String]
    var i=0
    for(canopyName <- cs.keys){
      val v = value(canopyName)
      if(v > _maxValue){
        _max=canopyName
      }
      i+=1
    }
    for(j<-0 until candidates.size){
      val canopy = sampleCanopy
      candidates(j) = canopy
      candidatesSet += canopy
    }
    //println("Reset canopies")
    //println("  canopyNames: "+this.canopyNames.size)
    //canopyNames.toSeq.foreach((s:String) => println("  " + s))
    println("  canopies: "+canopies.size)
    //var count = 0
    //println("  top canopies:")
    //for((k,v) <- canopies.toSeq.sortBy(_._2.size).reverse.take(10))
    //  println("    "+k+" size: "+v.size)
    //  count += v.size
    //}
    //println("  count: "+count)
    println("  candidates: "+candidates.size)
  }
  protected def sampleCanopy:String ={
    val e = mentions(random.nextInt(mentions.size))
    val canopy = e.canopyAttributes(random.nextInt(e.canopyAttributes.size))
    if(canopies.contains(canopy.canopyName))canopy.canopyName
    else canopyNames(random.nextInt(canopyNames.size))
  }
  protected def candidateSize = math.min(maxCandidateSize,canopies.size)
  def nextCanopy:String ={
    val sampledName = sampleCanopy
    val sampledLocIdx = random.nextInt(candidates.size)
    val sampledLoc = candidates(sampledLocIdx)
    if(value(sampledName) > value(sampledLoc) || random.nextDouble> 0.9)candidates(sampledLocIdx) = sampledName
    _sampled = candidates(sampledLocIdx)
    _sampled
  }
  override def accept = {
    super.accept
    candidates(random.nextInt(candidates.size)) = _sampled
  }
  override def print(n:Int=25):Unit ={
    /*
    if(1+1==2){
      val rateMap = new HashMap[String,Double]
      for(key <- candidates)rateMap += key -> value(key)
      //for(key <- countsAccept.keys)rateMap += key -> value(key)
      val sorted = rateMap.toList.sortBy(_._2).reverse.take(n)
      println("  Canopies: (rate, accepts, samples, size, name) #CANOP: "+rateMap.size)
      for((k,v) <- sorted){
        print("    "+v+", "+countsAccept.getOrElse(k,0)+", "+countsSamples.getOrElse(k,0)+", ")
        if(canopies.contains(k))println(canopies(k).size+", "+k) else println("0, "+k)
      }
      val sortedByAccepts = countsAccept.toList.sortBy(_._2).reverse.take(n)
      println("  Canopies: (rate, accepts, samples, size, name) #CANOP: "+rateMap.size)
      for((k,v) <- sortedByAccepts){
        println("    "+value(k)+", "+countsAccept.getOrElse(k,0)+", "+countsSamples.getOrElse(k,0)+", ")
        if(canopies.contains(k))println(canopies(k).size+", "+k) else println("0, "+k)
      }
      val sortedBySamples = countsSamples.toList.sortBy(_._2).reverse.take(n)
      println("  Canopies: (rate, accepts, samples, size, name) #CANOP: "+rateMap.size)
      for((k,v) <- sortedBySamples){
        println("    "+value(k)+", "+countsAccept.getOrElse(k,0)+", "+countsSamples.getOrElse(k,0)+", ")
        if(canopies.contains(k))println(canopies(k).size+", "+k) else println("0, "+k)
      }
    }*/
  }
}

class ParallelPaperSampler(model:Model,val numThreads:Int=25) extends PaperSampler(model) with ParallelSampling[PaperEntity]{
  def newSampler:BibSampler[PaperEntity] = new PaperSampler(model)
}
class ParallelAuthorSampler(model:Model,val numThreads:Int=25) extends AuthorSampler(model) with ParallelSampling[AuthorEntity]{
  def newSampler:BibSampler[AuthorEntity] = new AuthorSampler(model)
}
trait ParallelSampling[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable] extends BibSampler[E]{
  protected var samplers:Array[BibSampler[E]] = null
  def nonAssignedEntities = singletonCanopies
  val sampsPerMicroBatch=10
  def numThreads:Int
  def model:Model
  def newSampler:BibSampler[E]
  abstract override def timeAndProcess(n:Int):Unit ={
    loadBalancing
    resetSamplingStatistics
    for(sampler <- samplers.par){
      sampler.timeAndProcess(0)
    }
    println("n: "+n+" num threads: "+numThreads+" n/nt: "+(n/numThreads))
    var i = 0
    while(i<(n/(numThreads*sampsPerMicroBatch))){
      for(sampler <- samplers.par){
        val numA = sampler.numAccepted
        sampler.process(sampsPerMicroBatch)
        proposalCount += sampsPerMicroBatch
        numAccepted += sampler.numAccepted - numA
      }
      if(printInfo){
        if(i % 1000 ==0)print(".")
        if(i % 20000 ==0)printSamplingInfo()
      }
      i+=1
    }
  }
  protected def loadBalancing:Unit ={
    samplers = new Array[BibSampler[E]](numThreads)
    for(i<-0 until samplers.length)samplers(i) = newSampler
    for(sampler <- samplers){
      sampler.temperature = temperature
      println("PSampler temperature: "+sampler.temperature)
      sampler.printInfo=false
    }
    val bins = new Array[ArrayBuffer[E]](numThreads)
    for(i<-0 until bins.size)bins(i) = new ArrayBuffer[E]
    println("Num canopies: "+canopies.size)
    for((canopyName,canopy) <- canopies){
      val one = random.nextInt(bins.length)
      var two = random.nextInt(bins.length)
      while(two==one && numThreads>1)two=random.nextInt(bins.length)
      if(bins(one).size<bins(two).size)bins(one) ++= canopy else bins(two) ++= canopy
    }
    for(i<-0 until samplers.size){
      samplers(i).setEntities(bins(i))
    }
    println("Bin sizes for parallel sampler")
    for(bin <- bins)println("  "+bin.size)
    var totalSize = 0
    for(bin <- bins)totalSize+=bin.size
    println("  total distributed entities: "+totalSize)
  }
  abstract override def getEntities:Seq[E] = {
    samplers.flatMap(_.getEntities) ++ nonAssignedEntities
  }

}
trait SamplingStatistics{
  var printDotInterval=1000
  var printUpdateInterval=20000
  var proposalCount = 0
  protected var totalTime:Long=0L
  protected var intervalTime:Long=0L
  var printInfo=true
  var numAccepted = 0
  var numDiffVars = 0L
  var numDiffVarsInWindow=0
  var allDiffVarsInWindow=0
  var numNonTrivialDiffs=0
  def resetSamplingStatistics:Unit ={
    totalTime=System.currentTimeMillis
    intervalTime=totalTime
    numAccepted=0
    numDiffVars=0L
    numDiffVarsInWindow=0
    allDiffVarsInWindow=0
    numNonTrivialDiffs=0
    proposalCount=0
  }
}

/*
trait EntityPairStream[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable]{
  def nextPair:(E,E)
}
trait ParallelEntityPairStreamer[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable] extends EntityPairStream[E]{

}
*/

abstract class BibSampler[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable](model:Model) extends HierCorefSampler[E](model) with SamplingStatistics{
  protected var canopyStats:CanopyStatistics[E] = new ApproxMaxCanopySampling//( (Unit) => {this.getEntities})
  protected var canopies = new HashMap[String,ArrayBuffer[E]]
  protected var singletonCanopies = new ArrayBuffer[E]
  protected var _amountOfDirt = 0.0
  protected var _numSampleAttempts = 0.0 //TODO, could this overflow?
  override def timeAndProcess(n:Int):Unit = {
    if(printInfo)println("About to take "+ n + " samples.")
    totalTime=System.currentTimeMillis
    intervalTime=totalTime
    resetSamplingStatistics
    super.process(n)
  }
  //def newEntity = new AuthorEntity
  override def addEntity(e:E):Unit ={
    super.addEntity(e)
    for(cname <- e.canopyAttributes.map(_.canopyName)){
      canopies.getOrElse(cname,{val a = new ArrayBuffer[E];canopies(cname)=a;a}) += e
    }
  }
  override def setEntities(ents:Iterable[E]):Unit ={
    resetSamplingStatistics
    _amountOfDirt = 0.0
    _numSampleAttempts = 0.0
    singletonCanopies = new ArrayBuffer[E]
    canopies = new HashMap[String,ArrayBuffer[E]]
    super.setEntities(ents)
    singletonCanopies ++= canopies.filter(_._2.size==1).map(_._2.head)
    canopies = canopies.filter(_._2.size>1)
    canopyStats.reset(canopies)
    //println("Number of canopies: "+canopies.size)
    //for((k,v) <- canopies)println("  -"+k+":"+v.size)
  }
  def addEntities(ents:Iterable[E]):Unit ={
    for(e <- ents)addEntity(e)
    canopies = canopies.filter(_._2.size>1)
    canopyStats.addNewOnly(canopies)
  }
  override def nextEntity(context:E=null.asInstanceOf[E]):E = {
    var result:E=null.asInstanceOf[E]
    if(context==null)result=sampleEntity(entities)
    else {
      val cname = context.canopyAttributes.sampleUniformly(random).canopyName
      val canopy = canopies.getOrElse(cname,{val c = new ArrayBuffer[E];c+=context;c})
      result= if(canopy.size>0)sampleEntity(canopy) else sampleEntity(entities)//{val c = new ArrayBuffer[E];c+=context;c})
    }
    if(result==null)result = context
    result
  }
/*
  override def nextEntity(context:E=null.asInstanceOf[E]):E = {
    if(context==null)sampleDirtyEntity(entities)
    else sampleDirtyEntity(canopies(context.canopyAttributes.sampleUniformly(random).canopyName))
  }
  protected def sampleDirtyEntity(samplePool:ArrayBuffer[E]) ={
    var numTries = 10
    var result = sampleEntity(samplePool)
    _amountOfDirt += result.dirty.value.toDouble
    _numSampleAttempts += 1.0
    while(numTries>0 && result.dirty.value.toDouble<=(_amountOfDirt/_numSampleAttempts)){
      result = sampleEntity(samplePool)
      _amountOfDirt += result.dirty.value.toDouble
      _numSampleAttempts += 1.0
      numTries -= 1
    }
    //println("result dirty: "+result.dirty.value+ " average dirty: "+(_amountOfDirt/_numSampleAttempts))
    result
  }
  */

  /**Override to sample an entity proportion to the number of mentions in the subtree rooted here.*/
  /*
  override protected def sampleEntity(samplePool:ArrayBuffer[E])={
    var result = super.sampleEntity(samplePool)
    var i = 0.0
    var e = result
    while(e!=null){
      if(random.nextDouble<=1.0/i)result = e
      e = e.parentEntity.asInstanceOf[E]
      i += 1.0
    }
    result
  }
  */

  /*
  override def nextEntityPair:(E,E)={
    val sampledCanopyName = canopyStats.nextCanopy
    val canopy = canopyStats(sampledCanopyName)
    val m1 = sampleEntity(canopy)
    val m2 = sampleEntity(canopy)
    (m1,m2)
  }
  */
  override def mergeLeft(left:E,right:E)(implicit d:DiffList):Unit ={
    val oldParent = right.parentEntity
    right.setParentEntity(left)(d)
    propagateBagUp(right)(d)
    propagateRemoveBag(right,oldParent)(d)
    structurePreservationForEntityThatLostChild(oldParent)(d)
  }
  /**Jump function that proposes merge: entity1--->NEW-PARENT-ENTITY<---entity2 */
  override def mergeUp(e1:E,e2:E)(implicit d:DiffList):E = {
    val oldParent1 = e1.parentEntity
    val oldParent2 = e2.parentEntity
    val result = newEntity
    e1.setParentEntity(result)(d)
    e2.setParentEntity(result)(d)
    createAttributesForMergeUp(e1,e2,result)
    propagateRemoveBag(e1,oldParent1)(d)
    propagateRemoveBag(e2,oldParent2)(d)
    structurePreservationForEntityThatLostChild(oldParent1)(d)
    structurePreservationForEntityThatLostChild(oldParent2)(d)
    result
  }
  /**Peels off the entity "right", does not really need both arguments unless we want to error check.*/
  override def splitRight(left:E,right:E)(implicit d:DiffList):Unit ={
    val oldParent = right.parentEntity
    right.setParentEntity(null)(d)
    propagateRemoveBag(right,oldParent)(d)
    structurePreservationForEntityThatLostChild(oldParent)(d)
  }
  override def proposalHook(proposal:Proposal) = {
    super.proposalHook(proposal)
    if(proposal.diff.size>0){
      numAccepted += 1
      numDiffVars += proposal.diff.size.toLong
      numDiffVarsInWindow += proposal.diff.size
      numNonTrivialDiffs += 1
    }
    /*
    for(diff<-proposal.diff){
      diff.variable match{
        case bag:BagOfWordsVariable => bag.accept
        //case bag:TrueBow => bag.accept
        case _ => {}
      }
    }
    */

    if(proposal.diff.size>0 && proposal.modelScore>0){
      //println("Accepting jump")
      //println("  diff size: "+proposal.diff.size)
      //println("  score: "+proposal.modelScore)
      //println("  canopy: "+canopyStats.lastSampledName)
//      canopyStats.accept
    }
//    canopyStats.sampled

    proposalCount += 1
    if(printInfo){
      if(proposalCount % printDotInterval==0)
        print(".")
      if(proposalCount % printUpdateInterval==0)
      printSamplingInfo()
    }
  }
  def printSamplingInfo(count:Int = proposalCount):Unit ={
    var pctAccepted = numAccepted.toDouble/count.toDouble*100
    var timeDiffSec = ((System.currentTimeMillis-totalTime)/1000L).toInt
    var sampsPerSec:Int = -1
    var acceptedPerSec:Int = -1
    if(timeDiffSec!=0){
      sampsPerSec = count.toInt/timeDiffSec
      acceptedPerSec = numAccepted/timeDiffSec
    }
    println(" No. samples: "+count+", %accepted: "+pctAccepted+", time: "+(System.currentTimeMillis-intervalTime)/1000L+"sec., total: "+(System.currentTimeMillis-totalTime)/1000L+" sec.  Samples per sec: "+ sampsPerSec+" Accepted per sec: "+acceptedPerSec+". Avg diff size: "+(numDiffVarsInWindow)/(numNonTrivialDiffs+1))
    intervalTime = System.currentTimeMillis
    numDiffVarsInWindow=0
    allDiffVarsInWindow=0
    numNonTrivialDiffs=0
    //     canopyStats.print(5)
    Evaluator.eval(getEntities)
    System.out.flush
  }

  /*
  def printSamplingInfo:Unit ={
    if(proposalCount % printDotInterval==0)
      print(".")
    if(proposalCount % printUpdateInterval==0){
      var pctAccepted = numAccepted.toDouble/proposalCount.toDouble*100
      println(" No. samples: "+proposalCount+", %accepted: "+pctAccepted+", time: "+(System.currentTimeMillis-intervalTime)/1000L+"sec., total: "+(System.currentTimeMillis-totalTime)/1000L+" sec.  Samples per sec: "+ (proposalCount.toInt/(((System.currentTimeMillis-totalTime+1L)/1000L).toInt))+" Accepted per sec: "+(numAccepted/(((System.currentTimeMillis-totalTime+1L)/1000L).toInt))+". Avg diff size: "+(numDiffVarsInWindow)/(numNonTrivialDiffs+1))
      intervalTime = System.currentTimeMillis
      numDiffVarsInWindow=0
      allDiffVarsInWindow=0
      numNonTrivialDiffs=0
      //     canopyStats.print(5)
      Evaluator.eval(getEntities)
      System.out.flush
    }
  }
  */
  override def pickProposal(proposals:Seq[Proposal]): Proposal = {
    for(p <- proposals)allDiffVarsInWindow += p.diff.size
    super.pickProposal(proposals)
  }
  protected def propagateBagUp(entity:Entity)(implicit d:DiffList):Unit = EntityUtils.propagateBagUp(entity)(d)
  protected def propagateRemoveBag(parting:Entity,formerParent:Entity)(implicit d:DiffList):Unit = EntityUtils.propagateRemoveBag(parting,formerParent)(d)
  protected def createAttributesForMergeUp(e1:E,e2:E,parent:E)(implicit d:DiffList):Unit
}

/**Back-end implementations for prioritized and canopy based DB access and inference*/
trait BibDatabase{
  def authorColl:DBEntityCollection[AuthorEntity,AuthorCubbie]
  def paperColl:DBEntityCollection[PaperEntity,PaperCubbie]
}
abstract class MongoBibDatabase(mongoServer:String="localhost",mongoPort:Int=27017,mongoDBName:String="rexa2-cubbie") extends BibDatabase{
  import MongoCubbieConverter._
  import MongoCubbieImplicits._
  println("MongoBibDatabase: "+mongoServer+":"+mongoPort+"/"+mongoDBName)
  protected val mongoConn = new Mongo(mongoServer,mongoPort)
  protected val mongoDB = mongoConn.getDB(mongoDBName)
  val authorColl = new MongoDBBibEntityCollection[AuthorEntity,AuthorCubbie]("authors",mongoDB){
    def newEntityCubbie:AuthorCubbie = new AuthorCubbie
    def newEntity:AuthorEntity = new AuthorEntity
    def fetchFromCubbie(authorCubbie:AuthorCubbie,author:AuthorEntity):Unit = authorCubbie.fetch(author)
  }
  val paperColl = new MongoDBBibEntityCollection[PaperEntity,PaperCubbie]("papers",mongoDB){
    def newEntityCubbie:PaperCubbie = new PaperCubbie
    def newEntity:PaperEntity = new PaperEntity
    def fetchFromCubbie(paperCubbie:PaperCubbie,paper:PaperEntity):Unit = paperCubbie.fetch(paper)
    protected def promotionChanges:Seq[(String,String)] ={
      val result = new ArrayBuffer[(String,String)]
      for((id, cubbie) <- _id2cubbie){
        if(cubbie.pid.isDefined){
          val oldPromoted = cubbie.pid.value.toString
          val newPromoted = _id2entity(cubbie.pid.value).entityRoot.asInstanceOf[PaperEntity].promotedMention.value
          if(oldPromoted != newPromoted)result += oldPromoted -> newPromoted
        }
      }
      result
    }
    protected def promotionCreations(promotionChanges:Seq[(String,String)],entities:Seq[PaperEntity]) ={
      val result = new ArrayBuffer[PaperEntity]
      val promoted = new HashSet[String]
      for((oldPromoted,newPromoted) <- promotionChanges){
        promoted += oldPromoted
        promoted += newPromoted
      }
      for(e <- entities)
        if(e.promotedMention.value!=null)
          result += e
      result
    }
    protected def createPromotedMentions(promoted:PaperEntity):Unit ={
    }
    protected def changePromoted(oldPromotedId:String, newPromotedId:String):Unit ={
      println("CHANGE PROMOTED: "+oldPromotedId+" --> "+newPromotedId)
      entityCubbieColl.update(_.pid.set(oldPromotedId),_.pid.update(newPromotedId),false,true) //TODO: optimize this later, it is not necessary when entire paper entity is in memory,
      authorColl.entityCubbieColl.remove(_.pid.set(oldPromotedId).isMention.set(true).parentRef.exists(false)) //remove all singletons
      authorColl.entityCubbieColl.update(_.pid.set(oldPromotedId),_.pid.update(newPromotedId).inferencePriority.update(0.0),false,true) //TODO: we can optimize this also if all authors are in memory
    }
    override def store(entitiesToStore:Iterable[PaperEntity]):Unit ={
      super.store(entitiesToStore)
      for((oldPromotedId,newPromotedId) <- promotionChanges)changePromoted(oldPromotedId,newPromotedId)
    }
  }

  def add(papers:Iterable[PaperEntity]):Unit ={
    println("About to add papers to db.")
    //println("Authors: "+papers.flatMap(_.authors).size)
    print("Re-extracting names.")
    papers.foreach(_.authors.foreach((a:AuthorEntity)=>EntityUtils.reExtractNames(a.fullName)))
    println(" done.")
    print("Adding features for papers..")
    for(p <- papers)FeatureUtils.addFeatures(p)
    println(" done.")
    print("Inferring topics for papers....")
    for(lda<-Coref.ldaOpt)LDAUtils.inferTopicsForPapers(papers,lda)
    println(" done.")
    print("Adding features for authors....")
    for(p<-papers){
      p.promotedMention.set(p.id)(null)
      //addFeatures(p)
      for(a<-p.authors)FeatureUtils.addFeatures(a)
    }
    println(" done.")
    print("Inserting papers into DB...")
    paperColl.insert(papers)
    println(" done.")
    println("Inserting authors into DB...")
    var authorCount = 0
    var paperCount = 0
    for(paper <- papers){
      authorColl.insert(paper.authors)
      authorCount += paper.authors.size
      paperCount += 1
      if(paperCount % 1000==0)print(".")
      if(paperCount % 25000==0)println(" inserted "+paperCount + " papers and "+authorCount+" authors.")
    }
    //authorColl.insert(papers.flatMap(_.authors)) //too slow for large data
    println("Done.")
  }
  def createAuthorsFromPaper(p:PaperEntity):Unit ={

  }
  def insertLabeledRexaMentions(rexaFile:File):Unit ={
    val paperEntities = RexaLabeledLoader.load(rexaFile)
    add(paperEntities)
  }
  def insertMentionsFromBibDir(bibDir:File,useKeysAsIds:Boolean=false):Unit ={
    if(!bibDir.isDirectory)return insertMentionsFromBibFile(bibDir,useKeysAsIds)//println("Warning: "+bibDir + " is not a directory. Loading file instead.")
    println("Inserting mentions from bib directory: "+bibDir)
    var size = bibDir.listFiles.size
    var count = 0
    BibReader.skipped=0
    for(file <- bibDir.listFiles.filter(_.isFile)){
      insertMentionsFromBibFile(file,useKeysAsIds)
      count += 1
      if(count % 10 == 0)print(".")
      if(count % 200 == 0 || count==size)println(" Processed "+ count + " documents, but skipped "+BibReader.skipped + ".")
    }
    BibReader.skipped=0
  }
  def insertMentionsFromBibDirMultiThreaded(bibDir:File,useKeysAsIds:Boolean=false):Unit ={
    if(!bibDir.isDirectory)return insertMentionsFromBibFile(bibDir,useKeysAsIds)
    val papers = BibReader.loadBibTexDirMultiThreaded(bibDir,true,useKeysAsIds)
    add(papers)
  }
  def insertMentionsFromBibFile(bibFile:File,useKeysAsIds:Boolean,numEntries:Int = Integer.MAX_VALUE):Unit ={
    println("insertMentionsFromBibFile: use keys as ids: "+useKeysAsIds)
    val paperEntities = BibReader.loadBibTeXFile(bibFile,true,useKeysAsIds)
    println("Loaded "+paperEntities.size + " papers and "+paperEntities.flatMap(_.authors).size+" authors.")
    //for(ids <- idsToRetain)paperEntities = paperEntities.filter((p:PaperEntity)=>ids.contains(p.id))
    println("Done. About to add to DB")
    //if(!speedHackForLargeFiles)
    add(paperEntities)
    //else{
    //  for(paper <- paperEntities.par)add(Seq(paper))
    //}
    println("Done.")
  }
  def insertMentionsFromDBLP(location:String):Unit ={
    val paperEntities = DBLPLoader.loadDBLPData(location)
    add(paperEntities)
  }
  def splitFirst(firstName:String) : (String,String) ={
    var first:String = ""
    var middle : String = ""
    if(firstName==null)return ("","")
    val split=firstName.replaceAll("[\\.]",". ").replaceAll("[ ]+"," ").trim.split(" ",2)
    first = split(0)
    if(split.length==2)middle = split(1)
    (first,middle)
  }
  def filterFieldNameForMongo(s:String) = FeatureUtils.filterFieldNameForMongo(s)//s.replaceAll("[$\\.]","")
}


abstract class WeightedChildParentTemplate[A<:EntityAttr](implicit m:Manifest[A]) extends ChildParentTemplate[A] with DotFamily3[EntityRef,A,A]{
  lazy val weights = new la.GrowableDenseTensor1(ChildParentFeatureDomain.dimensionDomain.maxSize)
  //def statistics (eref:EntityRef#Value, child:A#Value, parent:A#Value) = new ChildParentFeatureVector[A](child, parent).value
}
class WeightedChildParentCosineDistance[B<:BagOfWordsVariable with EntityAttr](val name:String,val weight:Double=4.0,val shift:Double= -0.25)(implicit m:Manifest[B]) extends WeightedChildParentTemplate[B]{
  def statistics(eref:EntityRef#Value,childBow:B#Value,parentBow:B#Value) = new ChildParentBagsFeatureVector(name:String,childBow,parentBow).value
}
object ChildParentFeatureDomain extends CategoricalTensorDomain[String]{dimensionDomain.maxSize=10000}
class ChildParentFeatureVector[A<:EntityAttr](child:A#Value,parent:A#Value) extends FeatureVectorVariable[String]{
  def domain = ChildParentFeatureDomain
}
class ChildParentBagsFeatureVector[B<:BagOfWordsVariable with EntityAttr](name:String,childBow:B#Value,parentBow:B#Value)(implicit m:Manifest[B]) extends FeatureVectorVariable[String]{
  def domain = ChildParentFeatureDomain
  if(childBow.size>0 && parentBow.size>0){
    val cossim=childBow.cosineSimilarity(parentBow,childBow)
    if(cossim==0.0)this.+=(name+"sim0",1.0)
    if(cossim<0.01)this.+=(name+"sim<0.01",1.0)
    if(cossim<0.5)this.+=(name+"sim<0.01",0.5-cossim)
    if(cossim>=0.5)this.+=(name+"sim>0.5",cossim)
    /*
    val binNames = FeatureUtils.bin(cossim,name+"cosine")
    for(binName <- binNames){
      this += binName
//      this.+=(binName,1.0)
      //if(parentBow.l1Norm==2)features.update(binName+"ments=2",1.0)
      //if(parentBow.l1Norm>=2)features.update(binName+"ments>=2",1.0)
      //if(parentBow.l1Norm>=4)features.update(binName+"ments>=4",1.0)
      //if(parentBow.l1Norm>=8)features.update(binName+"ments>=8",1.0)
    }
    */
    //if(dot>0)features.update(name+"dot>0",1.0) else features.update(name+"dot=0",1.0)
//   this.+=(name+"cosine-no-shift",cossim)
    //features.+=(name+"cossine-shifted",cossim-0.5)
  }
}

//  this += new ChildParentTemplate[BagOfCoAuthors] with DotStatistics1[AuthorFeatureVector#Value]{
//class ParentChildCosineFeatureTemplate[B<:BagOfWordsVariable] extends ChildParentTemplate[B] with

/*
        new DotTemplateWithStatistics2[ChainNerLabel,TokenFeatures] {
      //def statisticsDomains = ((Conll2003NerDomain, TokenFeaturesDomain))
      lazy val weights = new la.DenseTensor2(Conll2003NerDomain.size, TokenFeaturesDomain.dimensionSize)
      def unroll1(label: ChainNerLabel) = Factor(label, label.token.attr[TokenFeatures])
      def unroll2(tf: TokenFeatures) = Factor(tf.token.attr[ChainNerLabel], tf)
    },

    val pieces = trainLabels.map(l => new SampleRankExample(l, new GibbsSampler(model, HammingObjective)))
    val learner = new SGDTrainer(model, new optimize.AROW(model))

      abstract class PairwiseTemplate extends Template3[PairwiseMention, PairwiseMention, PairwiseLabel] with Statistics[(BooleanValue,CorefAffinity)] {
    override def statistics(m1:PairwiseMention#Value, m2:PairwiseMention#Value, l:PairwiseLabel#Value) = {
      val mention1 = m1
      val mention2 = m2
      val coref: Boolean = l.booleanValue
      (null, null)
    }
  }
  abstract class PairwiseTransitivityTemplate extends Template3[PairwiseLabel,PairwiseLabel,PairwiseLabel] with Statistics[BooleanValue] {
    //def unroll1(p:PairwiseBoolean) = for (m1 <- p.edge.src.edges; m2 <- p.edge.dst.edges; if (m1)
  }



  object CorefAffinityDimensionDomain extends EnumDomain {
    val Bias, ExactMatch, SuffixMatch, EntityContainsMention, EditDistance2, EditDistance4, NormalizedEditDistance9, NormalizedEditDistance5, Singleton = Value
  }
  object CorefAffinityDomain extends CategoricalTensorDomain[String] {
    override lazy val dimensionDomain = CorefAffinityDimensionDomain
  }
  class CorefAffinity extends BinaryFeatureVectorVariable[String] {
    def domain = CorefAffinityDomain
  }
     */


/*
object CorefDimensionDomain extends EnumDomain {
  override def size=1000
  this.ensureSize(1000)
  override def frozen=false
  override def getValue(category:String): ValueType = {
    _frozen=false
    super.getValue(category)
  }
  //val Bias, ExactMatch, SuffixMatch, EntityContainsMention, EditDistance2, EditDistance4, NormalizedEditDistance9, NormalizedEditDistance5, Singleton = Value
}
object CorefDomain extends CategoricalVectorDomain[String] {
  override lazy val dimensionDomain = CorefDimensionDomain
  def printWeightsOld(model:TemplateModel){
    for(template<-model.familiesOfClass(classOf[DotFamily])){
      for(i<-template.weights.activeDomain){
        if(template.weights(i)!=0){
          val feature = dimensionDomain.getCategory(i)
          println(template.weights(i)+": "+feature)
        }
      }
    }
  }
  def printWeights(model:TemplateModel){
    println("\n====PRINTING WEIGHTS====")
    val positive = new ArrayBuffer[(String,Double)]
    val negative = new ArrayBuffer[(String,Double)]
    for(template<-model.familiesOfClass(classOf[DotFamily])){
      for(i<-template.weights.activeDomain){
        if(template.weights(i)>0)positive += dimensionDomain.getCategory(i) -> template.weights(i)
        else if(template.weights(i)<0)negative += dimensionDomain.getCategory(i) -> template.weights(i)
      }
    }
    val sortedPositive = positive.sortWith(_._2 < _._2)
    val sortedNegative = negative.sortWith(_._2 < _._2)
    for((feature,weight) <- sortedPositive)println(weight+":"+feature)
    for((feature,weight) <- sortedNegative)println(weight+":"+feature)
    println("POSITIVE: "+positive.size+" "+sortedPositive.size)
    println("NEGATIVE: "+negative.size+" "+negative.size)
  }
}
class AuthorFeatureVector extends FeatureVectorVariable[String] {
  def domain = CorefDomain
}

class ParameterizedAuthorCorefModel extends TemplateModel{
  this ++= (new AuthorCorefModel).templates
  this += new ChildParentTemplate[BagOfCoAuthors] with DotStatistics1[AuthorFeatureVector#Value]{
    val name="cpt-bag-coauth-"
    override def statisticsDomains = List(CorefDimensionDomain)
    //override def unroll1(er:EntityRef) = if(er.dst!=null)Factor(er, er.src.attr[BagOfCoAuthors], er.dst.entityRoot.attr[BagOfCoAuthors]) else Nil
    override def unroll2(childBow:BagOfCoAuthors) = Nil
    override def unroll3(parentBow:BagOfCoAuthors) = Nil
    //def statistics (values:Values) = Stat(new AffinityVector(values._2, values._3).value)
    def statistics(values:Values) = {
      val features = new AuthorFeatureVector
      val childBow = values._2
      val parentBow = values._3
      //val dot = childBow.deductedDot(parentBow,childBow)
      //if(dot == 0.0)
      //  features.update(name+"intersect=empty",childBow.l2Norm*parentBow.l2Norm - 0.25)
      if(childBow.size>0 && parentBow.size>0){
        val cossim=childBow.cosineSimilarity(parentBow,childBow)
        val binNames = FeatureUtils.bin(cossim,name+"cosine")
        for(binName <- binNames){
          features.update(binName,1.0)
          //if(parentBow.l1Norm==2)features.update(binName+"ments=2",1.0)
          //if(parentBow.l1Norm>=2)features.update(binName+"ments>=2",1.0)
          //if(parentBow.l1Norm>=4)features.update(binName+"ments>=4",1.0)
          //if(parentBow.l1Norm>=8)features.update(binName+"ments>=8",1.0)
        }
        //if(dot>0)features.update(name+"dot>0",1.0) else features.update(name+"dot=0",1.0)
      }
      //features.update(name+"cosine-no-shift",cossim)
      //features.update(name+"cossine-shifted",cossim-0.5)
      Stat(features.value)
    }
  }
  this += new ChildParentTemplate[BagOfVenues] with DotStatistics1[AuthorFeatureVector#Value]{
    override def statisticsDomains = List(CorefDimensionDomain)
    val name="cpt-bag-ven-"
    //override def unroll1(er:EntityRef) = if(er.dst!=null)Factor(er, er.src.attr[BagOfVenues], er.dst.entityRoot.attr[BagOfVenues]) else Nil
    override def unroll2(childBow:BagOfVenues) = Nil
    override def unroll3(childBow:BagOfVenues) = Nil
    def statistics(values:Values) = {
      val features = new AuthorFeatureVector
      val childBow = values._2
      val parentBow = values._3
      //val dot = childBow.deductedDot(parentBow,childBow)
      //if(dot == 0.0)
      //  features.update(name+"intersect=empty",childBow.l2Norm*parentBow.l2Norm)
      if(childBow.size>0 && (parentBow.size-childBow.size>0)){
        val cossim=childBow.cosineSimilarity(parentBow,childBow)
        val binNames = FeatureUtils.bin(cossim,name+"cosine")
        for(binName <- binNames)features.update(binName,1.0)
        //if(dot>0)features.update(name+"dot>0",1.0) else features.update(name+"dot=0",1.0)
      }
      Stat(features.value)
    }
  }
  this += new ChildParentTemplate[BagOfKeywords] with DotStatistics1[AuthorFeatureVector#Value]{
    override def statisticsDomains = List(CorefDimensionDomain)
    val name = "cpt-bag-keyw-"
    //override def unroll1(er:EntityRef) = if(er.dst!=null)Factor(er, er.src.attr[BagOfKeywords], er.dst.entityRoot.attr[BagOfKeywords]) else Nil
    override def unroll2(childBow:BagOfKeywords) = Nil
    override def unroll3(childBow:BagOfKeywords) = Nil
    def statistics(values:Values) ={
      val features = new AuthorFeatureVector
      val childBow = values._2
      val parentBow = values._3
      //val dot = childBow.deductedDot(parentBow,childBow)
      //if(dot == 0.0)
      //  features.update(name+"intersect=empty",childBow.l2Norm*parentBow.l2Norm)
      if(childBow.size>0 && (parentBow.size-childBow.size>0)){
        val cossim=childBow.cosineSimilarity(parentBow,childBow)
        val binNames = FeatureUtils.bin(cossim,name+"cosine")
        for(binName <- binNames)features.update(binName,1.0)
        //if(dot>0)features.update(name+"dot>0",1.0) else features.update(name+"dot=0",1.0)
      }
      Stat(features.value)
    }
  }
  this += new EntityBagTemplate[BagOfFirstNames]{
    def name:String = "ebt-firstName-"
    pwFeatures += new PWNameFeatures
  }
  this += new EntityBagTemplate[BagOfMiddleNames]{
    def name:String = "ebt-middleName-"
    pwFeatures += new PWNameFeatures
  }
  /*
  this += new Template1[BagOfCoAuthors] with DotStatistics1[AuthorFeatureVector#Value]{
    override def statisticsDomains = List(CorefDimensionDomain)
    val name = "bag-coauth-"
    def statistics(values:Values) ={
      val features = new AuthorFeatureVector
      val bag = values._1
      val size = bag.size.toDouble
      val weight = bag.l1Norm.toDouble
      val spread = if(size==0.0)0.0 else weight/size
      features.update(name+"spread", Math.exp(-spread))
      Stat(features.value)
    }
  }*/

/*
  this += new Template1[BagOfFirstNames] with DotStatistics1[AuthorFeatureVector#Value]{
    override def statisticsDomains = List(CorefDimensionDomain)
    val name = "bag-firstnames-"
    def statistics(values:Values) ={
      val features = new AuthorFeatureVector
      val bag = values._1
      val bagSeq = bag.iterator.toSeq
      var firstLetterMismatches = 0
      var nameMismatches = 0
      var i=0;var j=0;
      while(i<bagSeq.size){
        val (wordi,weighti) = bagSeq(i)
        j=i+1
        while(j<bagSeq.size){
          val (wordj,weightj) = bagSeq(j)
          if(wordi.charAt(0) != wordj.charAt(0))firstLetterMismatches += 1 //weighti*weightj
          else if(FeatureUtils.isInitial(wordi) && FeatureUtils.isInitial(wordj) && wordi.length==wordj.length && wordi!=wordj)firstLetterMismatches += 1
          if(wordi.length>1 && wordj.length>1 && !FeatureUtils.isInitial(wordi) && !FeatureUtils.isInitial(wordj) && wordi!=wordj)nameMismatches += 1 //wordi.editDistance(wordj)
          j += 1
        }
        i += 1
      }
      //if(firstLetterMismatches>1 || nameMismatches>1)
      //  println("FIRST NAME: "+firstLetterMismatches+" bag: "+bag)
      if(firstLetterMismatches>=1)features.update(name+"firstLetterMMGT1",1.0)
      //if(bag.variable.asInstanceOf[BagOfFirstNames].entity.attr[BagOfTruths].value.size==1)println("FIRST LETTER MM BAG: "+bag)
      //if(firstLetterMismatches>=2)features.update(name+"firstLetterMMGT2",1.0)
      //if(firstLetterMismatches>=4)features.update(name+"firstLetterMMGT4",1.0)
      //if(firstLetterMismatches>=8)features.update(name+"firstLetterMMGT8",1.0)
      if(nameMismatches>=1)features.update(name+"nameMMGT1",1.0)
      //if(nameMismatches>=2)features.update(name+"nameMMGT2",nameMismatches
      //if(nameMismatches>=4)features.update(name+"nameMMGT4",1.0)
      //if(nameMismatches>=8)features.update(name+"nameMMGT8",1.0)
      Stat(features.value)
    }
  }
  this += new Template1[BagOfMiddleNames] with DotStatistics1[AuthorFeatureVector#Value]{
    override def statisticsDomains = List(CorefDimensionDomain)
    val name = "bag-middlenames-"
    def statistics(values:Values) ={
      val features = new AuthorFeatureVector
      val bag = values._1
      val bagSeq = bag.iterator.toSeq
      var firstLetterMismatches = 0
      var nameMismatches = 0
      var i=0;var j=0;
      while(i<bagSeq.size){
        val (wordi,weighti) = bagSeq(i)
        j=i+1
        while(j<bagSeq.size){
          val (wordj,weightj) = bagSeq(j)
          if(wordi.charAt(0) != wordj.charAt(0))firstLetterMismatches += 1 //weighti*weightj
          else if(FeatureUtils.isInitial(wordi) && FeatureUtils.isInitial(wordj) && wordi.length==wordj.length && wordi!=wordj)firstLetterMismatches += 1
          if(wordi.length>1 && wordj.length>1 && !FeatureUtils.isInitial(wordi) && !FeatureUtils.isInitial(wordj) && wordi!=wordj)nameMismatches += 1 //wordi.editDistance(wordj)
          j += 1
        }
        i += 1
      }
      if(firstLetterMismatches>=1)features.update(name+"firstLetterMMGT1",1.0)
      //if(firstLetterMismatches>=2)features.update(name+"firstLetterMMGT2",1.0)
      //if(firstLetterMismatches>=4)features.update(name+"firstLetterMMGT4",1.0)
      //if(firstLetterMismatches>=8)features.update(name+"firstLetterMMGT8",1.0)
      if(nameMismatches>1)features.update(name+"nameMMGT1",1.0)
      //if(nameMismatches>2)features.update(name+"nameMMGT2",1.0)
      //if(nameMismatches>4)features.update(name+"nameMMGT4",1.0)
      //if(nameMismatches>8)features.update(name+"nameMMGT8",1.0)
      Stat(features.value)
    }
  }
  */

  this += new ChildParentTemplate[FullName] with DotStatistics1[AuthorFeatureVector#Value] {
    override def statisticsDomains = List(CorefDimensionDomain)
    val name = "cpt-fullname-"
    def statistics(v:Values) = {
      val features = new AuthorFeatureVector
      val childName = v._2
      val parentName = v._3
      val childFirst = FeatureUtils.normalizeName(childName(0)).toLowerCase
      val childMiddle = FeatureUtils.normalizeName(childName(1)).toLowerCase
      val childLast = FeatureUtils.normalizeName(childName(2)).toLowerCase
      val parentFirst = FeatureUtils.normalizeName(parentName(0)).toLowerCase
      val parentMiddle = FeatureUtils.normalizeName(parentName(1)).toLowerCase
      val parentLast = FeatureUtils.normalizeName(parentName(2)).toLowerCase
      if(childLast != parentLast)features.update(name+"cl!=pl",1.0)
      if(initialsMisMatch(childFirst,parentFirst))features.update(name+"initials:cf!=pf",1.0)
      if(initialsMisMatch(childMiddle,parentMiddle))features.update(name+"initials:cm!=pm",1.0)
      if(nameMisMatch(childFirst,parentFirst)){
        features.update(name+"nmm:cf!=pf",1.0)
        //if(parentName.entity.attr[BagOfTruths].value.size==1){
        //  println("NMM:CF!=PF")
        //  println("  "+childFirst+" "+parentFirst)
        //}
      }
      if(nameMisMatch(childMiddle,parentMiddle))features.update(name+"nmm:cm!=pm",1.0)
      //if(parentMiddle.length > childMiddle.length)result += 0.5
      //if(parentFirst.length > childFirst.length)result += 0.5
      //if((childMiddle.length==0 && parentMiddle.length>0) || (parentMiddle.length==0 && childMiddle.length>0))result -= 0.25
      Stat(features.value)
    }
    def initialsMisMatch(c:String,p:String):Boolean = (c!=null && p!=null && c.length>0 && p.length>0 && c.charAt(0)!=p.charAt(0))
    def nameMisMatch(c:String,p:String):Boolean = (c!=null && p!=null && !FeatureUtils.isInitial(c) && !FeatureUtils.isInitial(p) && c != p)
  }
  this += new Template3[EntityExists,IsEntity,IsMention] with DotStatistics1[AuthorFeatureVector#Value]{
    override def statisticsDomains = List(CorefDimensionDomain)
    val name = "structural-"
    def unroll1(exists:EntityExists) = Factor(exists,exists.entity.attr[IsEntity],exists.entity.attr[IsMention])
    def unroll2(isEntity:IsEntity) = Factor(isEntity.entity.attr[EntityExists],isEntity,isEntity.entity.attr[IsMention])
    def unroll3(isMention:IsMention) = throw new Exception("An entitie's status as a mention should never change.")
    def statistics(v:Values) ={
      val features = new AuthorFeatureVector
      val exists:Boolean = v._1.booleanValue
      val isEntity:Boolean = v._2.booleanValue
      val isMention:Boolean = v._3.booleanValue
      if(exists && isEntity) features.update(name+"entity",1.0)
      //if(exists && !isEntity && !isMention)result -= subEntityExistenceCost
      Stat(features.value)
    }
  }
  this += new StructuralPriorsTemplate(0.0,-0.25)
  //this += new StructuralPriorsTemplate(1.0,0.25)
  //this += new StructuralPriorsTemplate(4.0,0.25)
}


trait PWBagFeatures{
  var accumulated = 0.0
  var conditionalNormalizer = 0.0
  def reset:Unit = {
    accumulated = 0.0
    conditionalNormalizer = 0.0
  }
  def exists:Boolean = (accumulated>0.0)
  def forAll:Boolean = (accumulated==conditionalNormalizer)
  def avg:Double = accumulated/conditionalNormalizer
  def name:String
  def condition(si:String,wi:Double,sj:String,wj:Double):Boolean
  def value(si:String,wi:Double,sj:String,wj:Double):Double
  def process(si:String,wi:Double,sj:String,wj:Double, selfCompare:Boolean):Unit ={
    if(condition(si,wi,sj,wj)){
      val v = value(si,wi,sj,wj)
      //println("si: "+si+" sj: "+sj)
      //println("  value: "+v)
      val weight = if(selfCompare)wi*(1.0 - wi) else wi * wj
      accumulated += v * weight
      conditionalNormalizer += 1.0 * weight
    }
  }
  def defaultFeatures:Iterable[(String,Double)] = {
    val result = new ArrayBuffer[(String,Double)]
    if(conditionalNormalizer>0.0){
      if(exists)result += (name+"exists") -> 1.0 else result += (name+"!exists") -> 1.0
      //if(forAll)result += (name+"forall") -> 1.0 else result += (name+"!forall") -> 1.0
      result += (name+"avg") -> avg
    }
    result
  }
}

class PWNameFeatures extends PWBagFeatures{
  def name = "pwnf-"
  def condition(si:String,wi:Double,sj:String,wj:Double):Boolean = (si.length>0 && sj.length>0)
  def value(si:String,wi:Double,sj:String,wj:Double):Double = if((!(!FeatureUtils.isInitial(si) ^ FeatureUtils.isInitial(sj)) && si!=sj) || (si.charAt(0) != sj.charAt(0)))1.0 else 0.0 //if(si!=sj) wi*wj//1.0 else 0.0
}

abstract class EntityBagTemplate[V<:BagOfWordsVariable with EntityAttr](implicit m:Manifest[V]) extends Template2[V,IsEntity] with DotStatistics1[AuthorFeatureVector#Value]{
  override def statisticsDomains = List(CorefDimensionDomain)
  def unroll1(bag:V) = if(bag.entity.isRoot)Factor(bag,bag.entity.attr[IsEntity]) else Nil
  def unroll2(isEntity:IsEntity) = if(isEntity.entity.isRoot)Factor(isEntity.entity.attr[V],isEntity) else Nil
  //def unroll1(bag:V) = if(bag.entity.isRoot)Factor(bag) else Nil
  def name:String
  val pwFeatures = new ArrayBuffer[PWBagFeatures]
  def statistics(values:Values) ={
    val features = new AuthorFeatureVector
    val bag = values._1
    val bagSeq = bag.iterator.toSeq
    var firstLetterMismatches = 0
    var nameMismatches = 0
    var i=0;var j=0;
    for(pwf<-pwFeatures)pwf.reset
    while(i<bagSeq.size){
      val (wordi,weighti) = bagSeq(i)
      j=i
      while(j<bagSeq.size){
        val (wordj,weightj) = bagSeq(j)
        for(pwf<-pwFeatures)pwf.process(wordi,weighti,wordj,weightj, (i==j))
        j += 1
      }
      i += 1
    }
    for(pwf<-pwFeatures)
      for((featureName,featureWeight) <- pwf.defaultFeatures)
        features.update(name+featureName, featureWeight)
    Stat(features.value)
  }
}
*/


