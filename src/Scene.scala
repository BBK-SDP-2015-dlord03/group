import java.io.FileReader
import java.io.LineNumberReader

import scala.annotation.tailrec

import Messages.RenderLine
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala

object Scene {

  import java.io.{ FileReader, LineNumberReader }
  import scala.annotation.tailrec

  def fromFile(file: String) = {
    val in = new LineNumberReader(new FileReader(file))
    val (objects, lights) = readLines(in, Nil, Nil)
    new Scene(objects, lights)
  }

  @tailrec
  private def readLines(in: LineNumberReader, objects: List[Shape], lights: List[Light]): (List[Shape], List[Light]) = {
    val line = in.readLine
    if (line == null) {
      (objects, lights) match {
        case (Nil, _) => throw new RuntimeException("no objects")
        case (_, Nil) => throw new RuntimeException("no lights")
        case (os, ls) => (os.reverse, ls.reverse)
      }
    } else {
      val fields = line.replaceAll("#.*", "").trim.split("\\s+").filter(_ != "")
      fields.headOption match {
        case Some("sphere") =>
          val Array(x, y, z, rad, r, g, b, shine) = fields.tail.map(_.toFloat)
          readLines(in, Sphere(Vector(x, y, z), rad, Colour(r, g, b), shine) :: objects, lights)
        case Some("light") =>
          val Array(x, y, z, r, g, b) = fields.tail.map(_.toFloat)
          readLines(in, objects, Light(Vector(x, y, z), Colour(r, g, b)) :: lights)
        case None =>
          // blank line
          readLines(in, objects, lights)
        case Some(x) =>
          throw new RuntimeException("unknown command: " + x)
      }
    }
  }
}

class Scene private (val objects: List[Shape], val lights: List[Light]) {

  private def this(p: (List[Shape], List[Light])) = this(p._1, p._2)

  /** Level of background lighting. */
  val ambient = .8f

  /** Image background colour. */
  val background = Colour.black

  /** Viewing point */
  val eye = Vector.origin

  /** Viewing angle **/
  val angle = 90f

  def traceImage(width: Int, height: Int, context: ActorContext, coordinator: ActorRef) {
      
    import Messages.RenderLine
    
    val frustum = (.5 * angle * math.Pi / 180).toFloat
    val cosf = math.cos(frustum)
    val sinf = math.sin(frustum)

    for (line <- 0 until height) {
      context.actorOf(Props[Tracer], s"TracerActor-${line}") ! RenderLine(this, line, width, height, frustum, coordinator)
    }
    
  }

  def shadow(ray: Ray, l: Light): Boolean = {
    val distSquared = (l.loc - ray.orig).normSquared
    intersections(ray).foreach {
      case (v, o) =>
        if ((v - ray.orig).normSquared < distSquared)
          return true
    }
    false
  }

  // Compute the colour contributed by light l at point v on object o.
  def shade(ray: Ray, l: Light, v: Vector, o: Shape): Colour = {
    val toLight = Ray(v, (l.loc - v).normalized)

    val N = o.normal(v)

    // Diffuse light
    if (shadow(toLight, l) || (N dot toLight.dir) < 0)
      Colour.black
    else {
      // diffuse light
      val diffuse = o.colour * (N dot toLight.dir)

      println("ray " + ray)
      println("diffuse " + diffuse)

      // specular light
      val R = reflected(-toLight.dir, N)
      val len = ray.dir.norm * R.norm

      val specular = if (len > 1e-12) {
        // Want the angle between R and the vector TO the eye

        val cosalpha = -(ray.dir dot R) / len

        val power = if (cosalpha > 1e-12) math.pow(cosalpha, o.shine).toFloat else 0.0f

        if (power > 1e-12) {
          val scale = o.reflect * power
          l.colour * o.specular * scale
        } else
          Colour.black
      } else
        Colour.black

      println("specular " + specular)

      val color = diffuse + specular

      println("color " + color + " 0x" + color.rgb.toHexString)

      color
    }
  }

  def reflected(v: Vector, N: Vector): Vector = v - (N * 2.0f * (v dot N))

  def intersections(ray: Ray) = objects.flatMap {
    o => o.intersect(ray).map { v => (v, o) }
  }

  def closestIntersection(ray: Ray) = intersections(ray).sortWith {
    case ((v1, o1), (v2, o2)) => {
      val q1 = (v1 - ray.orig).normSquared
      val q2 = (v2 - ray.orig).normSquared
      q1 < q2
    }
  }.headOption

  val maxDepth = 3

  def trace(vector: Vector): Colour = trace(Ray(eye, vector), maxDepth)
  
  def trace(ray: Ray): Colour = trace(ray, maxDepth)

  private def trace(ray: Ray, depth: Int): Colour = {
    
    Trace.incrementRayCount

    // Compute the intersections of the ray with every object, sort by
    // distance from the ray's origin and pick the closest to the origin.
    val r = closestIntersection(ray)

    r match {
      case None => {
        // If no intersection, the colour is black
        background
      }
      case Some((v, o)) => {
        // Compute the color as the sum of:

        Trace.incrementHitCount

        // The contribution of each point light source.
        var c = lights.foldLeft(Colour.black) {
          case (c, l) => c + shade(ray, l, v, o)
        }

        // The contribution of the ambient light.
        c += o.colour * ambient

        // Return the colour.
        c
      }
    }
  }
}
