/*
 * This class is used to draw a single drawing primitive using "characteristics"
 * (color, line style, that sort of thing) that can be set.  The object contains 
 * an optional list of child objects.  The characteristics will apply to the
 * child objects, but not to any "siblings" (i.e. fellow objects in a list) that
 * this object has.  The child objects are contained in a ArrayDeque that is
 * inherited - child objects can be added and deleted using normal ArrayDeque
 * functions.  HOWEVER, if objects are being changed while they are actively
 * being drawn, the following ArrayDeque functions have been overridden and made
 * "safe" with mutex locks to avoid concurrent change errors:
 *     add()
 *     addLast() - this is the same as "add()"
 *     addFirst()
 *     clear()
 *     removeFirst()
 *     removeLast()
 *     remove( DrawObject )
 * Any external use of the ArrayDeque (using iterators or whatever) should surround
 * itself with a "synchronized( THIS OBJECT ) {}" structure to avoid concurrent
 * exceptions.
 */
package mil.navy.usno.plotlib;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.Line2D;
import java.awt.geom.GeneralPath;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.Font;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.geom.Point2D;
import java.awt.geom.AffineTransform;
import java.io.IOException;

/*
 * This is all xml parsing stuff....might be useful for something..

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;

import org.xml.sax.InputSource;
import java.io.StringReader;

            //  Parse the input text using the absurdly complex XML stuff that
            //  Java provides
            try {
                DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                org.w3c.dom.Document dom = db.parse( new InputSource( new StringReader( "<text>" + text + "</text>" ) ) );
                org.w3c.dom.Element te = dom.getDocumentElement();
            } catch ( javax.xml.parsers.ParserConfigurationException e ) {
                System.out.println( e );
            } catch ( org.xml.sax.SAXException e ) {
                System.out.println( e );
            } catch ( java.io.IOException e ) {
                System.out.println( e );
            }
 * 
 */

public class DrawObject extends ArrayDeque<DrawObject> {
    
    public DrawObject() {
        _scaleSet = false;
        _translateSet = false;
        _rotateSet = false;
        _fontSet = false;
        _clipSet = false;
        _colorSet = false;
        _lineWidthSet = false;
        _lineCapSet = false;
        _lineJoinSet = false;
        type = EMPTY;
        _visible = true;
    }
    
    /*
     * Safe versions of ArrayDeque functions.
     */
    public boolean add( DrawObject e ) {
        synchronized( this ) {
            super.add( e );
        }
        return true;
    }
    public void addFirst( DrawObject e ) {
        synchronized( this ) {
            super.addFirst( e );
        }
    }
    public boolean remove( DrawObject e ) {
        boolean ret = false;
        synchronized( this ) {
            ret = super.remove( e );
        }
        return ret;
    }
    public DrawObject removeFirst() {
        DrawObject ret = null;
        synchronized( this ) {
            ret = super.removeFirst();
        }
        return ret;
    }
    public DrawObject removeLast() {
        DrawObject ret = null;
        synchronized( this ) {
            ret = super.removeLast();
        }
        return ret;
    }
    public void clear() {
        synchronized( this ) {
            super.clear();
        }
    }
    
    //--------------------------------------------------------------------------
    //! Draw the data associated with this object, as well as its children.  Before
    //! drawing, set any scales, offsets, fonts, fontsizes, or whatever
    //! else is associated with this object.  These settings will apply to this
    //! object and its children, but NOT to parent or sibling objects.
    //! This function also measures the "offset" in x and y caused by drawing
    //! itself and its children, putting the results in the "offsets" array.
    //! If you want to measure the offsets but not draw anything, the boolean
    //! measureOnly should be set to true.  The offsets array can also be set to
    //! null if the offsets are not interesting - this should make drawing faster
    //! (although not noticeably in in my experience so far).
    //--------------------------------------------------------------------------
    public void draw( Graphics2D g, GeneralPath currentPath, double[] offsets, boolean measureOnly ) {
        
        synchronized( this ) {
                
            //  Zero the offsets, if there are any.
            if ( offsets!= null ) {
                offsets[0] = 0.0;
                offsets[1] = 0.0;
            }

            //  Bail out immediately if this is not a visible object.
            if ( !_visible )
                return;

            //  Create a new graphics context to which characteristics changes can
            //  be applied.  If there are no changes to characteristics, use the
            //  existing graphics context.
            if ( _scaleSet | _translateSet | _rotateSet | _fontSet | _clipSet | 
                    _colorSet | type == COMPLEX_TEXT | _fontBold | _fontItalic | _unscaled )
                _drawGraphics = (Graphics2D)( g.create() );
            else
                _drawGraphics = g;

            //  Apply characteristics changes to the new graphics context.
            if ( _scaleSet )
                _drawGraphics.scale( _xScale, _yScale );
            if ( _unscaled ) {
                System.out.println( "before: " + _drawGraphics.getTransform() );
                _drawGraphics.scale( 1.0 / _drawGraphics.getTransform().getScaleX(),
                        1.0 / _drawGraphics.getTransform().getScaleY() );
                System.out.println( "after:  " + _drawGraphics.getTransform() );
            }
            if ( _translateSet )
                _drawGraphics.translate( _xOff, _yOff );
            if ( _rotateSet )
                _drawGraphics.rotate( _rotate );
            if ( _colorSet ) {
                _drawGraphics.setColor( _color );
            }
            if ( _clipSet ) {
                _drawGraphics.setClip( _clipShape );
            }
            if ( _fontSet ) {
                int fontStyle = 0;
                if ( _fontBold )
                    fontStyle |= Font.BOLD;
                if ( _fontItalic )
                    fontStyle |= Font.ITALIC;
                _drawGraphics.setFont( new Font( _font, fontStyle, (int)_fontSize ) );
            }
            else if ( _fontBold || _fontItalic || _fontScale != null || _fontName != null | _fontY != null ) {
                //  If the entire font wasn't set, check for settings of individual
                //  font characteristics
                Font oldFont = _drawGraphics.getFont();
                String font = oldFont.getName();
                if ( _fontName != null )
                    font = _fontName;
                double fontSize = (double)oldFont.getSize();
                if ( _fontScale != null )
                    fontSize *= _fontScale.doubleValue();
                int fontStyle = 0;
                if ( oldFont.isBold() || _fontBold )
                    fontStyle |= Font.BOLD;
                if ( oldFont.isItalic() || _fontItalic )
                    fontStyle |= Font.ITALIC;
                _drawGraphics.setFont( new Font( font, fontStyle, (int)fontSize ) );
                if ( _fontY != null )
                    _drawGraphics.translate( 0.0, (double)oldFont.getSize() * _fontY.doubleValue() );
            }
            if ( _lineWidthSet | _lineCapSet | _lineJoinSet ) {
                BasicStroke oldStroke = (BasicStroke)( _drawGraphics.getStroke() );
                float thisWidth;
                if ( _lineWidthSet )
                    thisWidth = (float)_lineWidth;
                else
                    thisWidth = oldStroke.getLineWidth();
                int thisCap;
                if ( _lineCapSet )
                    thisCap = _lineCap;
                else
                    thisCap = oldStroke.getEndCap();
                int thisJoin;
                if ( _lineJoinSet )
                    thisJoin = _lineJoin;
                else
                    thisJoin = oldStroke.getLineJoin();
                float thisMiter = oldStroke.getMiterLimit();
                float[] thisStyle;
                if ( _lineStyleSet ) {
                    thisStyle = new float[_lineStyle.length];
                    for ( int i = 0; i < _lineStyle.length; ++i )
                        thisStyle[i] = _lineStyle[i] * thisWidth;
                }
                else
                    thisStyle = oldStroke.getDashArray();
                float thisPhase = oldStroke.getDashPhase();
                _drawGraphics.setStroke( new BasicStroke( thisWidth, thisCap, thisJoin, thisMiter, thisStyle, thisPhase ) );
            }

            //  This is where we actually do the drawing for this object.  What we
            //  draw depends on the object type.
            switch ( type ) {

                //  An EMPTY object draws nothing.  It can be used as a parent object
                //  for holding a tree of descendents, and may optionally change
                //  settings for those descendents.
                case EMPTY:
                default:
                    break;

                //  A TEXT object draws a string at a particular position (the
                //  position is the lower left corner).  It can be centered,
                //  left justified or right justified (left by default).
                case TEXT:
                    //  Draw if we are drawing...
                    if ( !measureOnly )
                        _drawGraphics.drawString( textString, (float)_x1, (float)_y1 );
                    //  ...and measure if we are measuring.
                    if ( offsets != null )
                        offsets[0] = _drawGraphics.getFontMetrics().stringWidth( textString );
                    break;
                    
                //  This text function draws text at the "current point", which may
                //  be set by "vertex".  If there is no current point, the text is put
                //  at 0, 0.
                case FLOATING_TEXT:
                    //  Draw using the current point if it exists, 0,0 if not.
                    if ( !measureOnly ) {
                        double x = 0.0;
                        double y = 0.0;
                        if ( currentPath == null ) {
                            currentPath = new GeneralPath();
                        }
                        else {
                            x = currentPath.getCurrentPoint().getX();
                            y = currentPath.getCurrentPoint().getY();
                        }
                        _drawGraphics.drawString( textString, (float)x, (float)y );
                        currentPath.moveTo( _drawGraphics.getFontMetrics().stringWidth( textString ), 0.0 );
                    }
                    //  Measure the width like a normal string
                    if ( offsets != null )
                        offsets[0] = _drawGraphics.getFontMetrics().stringWidth( textString );
                    break;

                //  A COMPLEX_TEXT object simply measures and justifies child objects
                //  (which are often text, but don't need to be).  It then manipulates
                //  the offsets of those objects to ensure they are drawn according
                //  to the justification instructions.
                case COMPLEX_TEXT:
                    //  Measure child objects
                    double totalOffsets[] = new double[2];
                    double newOffsets[] = new double[2];
                    for ( Iterator<DrawObject> iter = this.iterator(); iter.hasNext(); ) {
                        DrawObject thisObject = iter.next();
                        //  Translate this object based on the size of the previous object
                        //  (which might be zero!).
                        thisObject.translate( newOffsets[0], newOffsets[1] );
                        //  This does the measuring...(note the use of "true" for measureOnly)
                        thisObject.draw( _drawGraphics, currentPath, newOffsets, true );
                        //  Change the offsets for our justification
                        totalOffsets[0] += newOffsets[0];
                        totalOffsets[1] += newOffsets[1];
                    }
                    //  Find our position if this is "floating" text.
                    if ( _floatingText && currentPath != null ) {
                        _x1 = currentPath.getCurrentPoint().getX();
                        _y1 = currentPath.getCurrentPoint().getY();
                    }
                    //  Change our current translation to match the justification
                    if ( _justify == this.RIGHT_JUSTIFY )
                        _drawGraphics.translate( _x1 - totalOffsets[0], _y1 + totalOffsets[1] );
                    else if ( _justify == this.CENTER_JUSTIFY )
                        _drawGraphics.translate( _x1 - totalOffsets[0] / 2.0, _y1 + totalOffsets[1] );
                    else
                        _drawGraphics.translate( _x1, _y1 );
                    //  Create a new current path.  We use the current path to locate text.
                    currentPath = new GeneralPath();
                    currentPath.moveTo( 0.0, 0.0 );
                    break;

                //  Many of the "draw" objects simply draw a pre-specified shape.
                case DRAWRECT:
                case DRAWLINE:
                case DRAWPOLY:
                    _drawGraphics.draw( _shape );
                    break;

                //  Fill the pre-specified shape.
                case FILLRECT:
                case FILLPOLY:
                    _drawGraphics.fill( _shape ); 
                    break;

                //  Start a completely new path.
                case NEWPATH:
                    currentPath = new GeneralPath();
                    break;

                //  Draw a line through the current path.
                case STROKEPATH:
                    if ( currentPath != null )
                        _drawGraphics.draw( currentPath );
                    break;

                //  Fill the current path.
                case FILLPATH:
                    if ( currentPath != null )
                        _drawGraphics.fill( currentPath );
                    break;

                //  Close the current path.
                case CLOSEPATH:
                    if ( currentPath != null )
                        currentPath.closePath();
                    break;

                //  Add a vertex to the current path.  If there is no current path,
                //  start one.
                case VERTEX:
                    if ( currentPath == null )
                        currentPath = new GeneralPath();
                    //  If the path is empty, move to the current point.  Otherwise
                    //  draw a line to it.
                    if ( currentPath.getCurrentPoint() == null )
                        currentPath.moveTo( _x1, _y1 );
                    else
                        currentPath.lineTo( _x1, _y1 );
                    break;
                    
                //  Add a vertex to the current path that has a position relative
                //  to the existing path (if there is one).
                case RELATIVE_VERTEX:
                    if ( currentPath == null )
                        currentPath = new GeneralPath();
                    if ( currentPath.getCurrentPoint() == null )
                        currentPath.moveTo( _x1, _y1 );
                    else
                        currentPath.lineTo( currentPath.getCurrentPoint().getX() + _x1,
                                currentPath.getCurrentPoint().getY() + _y1 );
                    break;

                //  Add a curve to the current path.  This will only work if there
                //  is a current path.
                case CURVE:
                    if ( currentPath != null && currentPath.getCurrentPoint() == null )
                        currentPath.curveTo( _x1, _y1, _x2, _y2, _x3, _y3 );
                    break;

                case DRAWIMAGE:
                    _drawGraphics.drawRenderedImage( _image, _drawGraphics.getTransform().getTranslateInstance( _x1, _y1 ) );
            }

            //  Draw all children of this object.
            for ( Iterator<DrawObject> iter = this.iterator(); iter.hasNext(); ) {
                if ( offsets != null ) {
                    double newOffsets[] = new double[2];
                    iter.next().draw( _drawGraphics, currentPath, newOffsets, measureOnly );
                    offsets[0] += newOffsets[0];
                    offsets[1] += newOffsets[1];
                }
                else
                    iter.next().draw( _drawGraphics, currentPath, null, measureOnly );
            }

        }

    }
    
    public void scale( double newXScale, double newYScale ) {
        synchronized( this ) {
            _xScale = newXScale;
            _yScale = newYScale;
            _scaleSet = true;
        }
    }
    public void scaleOff() {
        synchronized( this ) {
            _scaleSet = false;
        }
    }
    public void translate( double newXOff, double newYOff ) {
        synchronized( this ) {
            _xOff = newXOff;
            _yOff = newYOff;
            _translateSet = true;
        }
    }
    public void translateOff() {
        synchronized( this ) {
            _translateSet = false;
        }
    }
    public void rotate( double newRotate ) {
        synchronized( this ) {
            _rotate = newRotate;
            _rotateSet = true;
        }
    }
    public void rotateOff() {
        synchronized( this ) {
            _rotateSet = false;
        }
    }
    public void font( Font newFont ) {
        synchronized( this ) {
            _theFont = newFont;
            // We save the aspects of a font because we will have to set it anyway.
            // We can't change the scale of fonts that have been set.
            _font = newFont.getName();
            _fontSize = (double)newFont.getSize();
            if ( newFont.isBold() )
                _fontBold = true;
            else
                _fontBold = false;
            if ( newFont.isItalic() )
                _fontItalic = true;
            else
                _fontItalic = false;
            _fontSet = true;
        }
    }
    public void fontOff() {
        synchronized( this ) {
            _font = null;
            _fontSet = false;
            _theFont = null;
        }
    }
    public Font font() {
        return _theFont;
    }
    
    //--------------------------------------------------------------------------
    //!  Make the existing font bold.
    //--------------------------------------------------------------------------
    public void fontBold( boolean newVal ) {
        _fontBold = newVal;
    }
    public boolean fontBold() { return _fontBold; }
    
    //--------------------------------------------------------------------------
    //!  Make the existing font italic.
    //--------------------------------------------------------------------------
    public void fontItalic( boolean newVal ) {
        _fontItalic = newVal;
    }
    public boolean fontItalic() { return _fontItalic; }
    
    //--------------------------------------------------------------------------
    //!  Change the name of the existing font (font family, whatever...)
    //--------------------------------------------------------------------------
    public void fontName( String newVal ) {
        _fontName = newVal;
    }
    public String fontName() { return _fontName; }
    
    //--------------------------------------------------------------------------
    //!  Apply a multiple to the existing font size
    //--------------------------------------------------------------------------
    public void fontScale( double newVal ) {
        _fontScale = new Double( newVal );
    }
    public Double fontScale() { return _fontScale; }
    
    //--------------------------------------------------------------------------
    //!  Apply a y-offset to text expressed as a multiple of the existing font 
    //!  size.
    //--------------------------------------------------------------------------
    public void fontY( double newVal ) {
        _fontY = new Double( newVal );
    }
    
    public void setClip( double x, double y, double w, double h ) {
        synchronized( this ) {
            _clipShape = new Rectangle2D.Double( x, y, w, h );
            _clipSet = true;
        }
    }
    public void setClipOff() {
        synchronized( this ) {
            _clipSet = false;
        }
    }
    public void color( Color newColor ) {
        synchronized( this ) {
            _color = newColor;
            _colorSet = true;
        }
    }
    public Color color() {
        return _color;
    }
    public void colorOff() {
        synchronized( this ) {
            _colorSet = false;
        }
    }
    public boolean colorSet() { return _colorSet; }
    public void lineWidth( double newWidth ) {
        synchronized( this ) {
            _lineWidth = newWidth;
            _lineWidthSet = true;
        }
    }
    public double lineWidth() {
        return _lineWidth;
    }
    public void lineWidthOff() {
        synchronized( this ) {
            _lineWidthSet = false;
        }
    }
    public boolean lineWidthSet() {
        return _lineWidthSet;
    }
    public void lineCap( int newCap ) {
        synchronized( this ) {
            _lineCap = newCap;
            _lineCapSet = true;
        }
    }
    public int lineCap() {
        return _lineCap;
    }
    public void lineCapOff() {
        synchronized( this ) {
            _lineCapSet = false;
        }
    }
    public boolean lineCapSet() {
        return _lineCapSet;
    }
    public void lineJoin( int newJoin ) {
        synchronized( this ) {
            _lineJoin = newJoin;
            _lineJoinSet = true;
        }
    }
    public int lineJoin() {
        return _lineJoin;
    }
    public void lineJoinOff() {
        synchronized( this ) {
            _lineJoinSet = false;
        }
    }
    public boolean lineJoinSet() {
        return _lineJoinSet;
    }
    //--------------------------------------------------------------------------
    //! The line style is governed by an array of floats that tells the system
    //! when to draw a line as opaque and transparent.  These arrays can be as
    //! simple or complicated as you like - whatever pattern you include will be
    //! repeated as many times as necessary for drawing the line.  A null array 
    //! indicates a solid line.
    //! 
    //! See documentation on java.awt.BasicStroke for more details about the
    //! arrays.
    //! 
    //! DrawObjects have some pre-defined arrays containing commonly used line
    //! styles.  You can use these or specify your own array.
    //! 
    //! Note that Java does not scale the line style arrays to the line thickness,
    //! however DrawObjects do.  Thus the dashes in a dashed line will expand as
    //! the line grows thicker.
    //--------------------------------------------------------------------------
    public void lineStyle( int newStyle ) {
        synchronized( this ) {
            switch( newStyle ) {
                default:
                case LINE_SOLID:
                    _lineStyle = null;
                    break;
                case LINE_DASH:
                    _lineStyle = new float[2];
                    _lineStyle[0] = (float)5.0;
                    _lineStyle[1] = (float)5.0;
                    break;
                case LINE_DOT:
                    _lineStyle = new float[2];
                    _lineStyle[0] = (float)1.0;
                    _lineStyle[1] = (float)3.0;
                    break;
                case LINE_DASHDOT:
                    _lineStyle = new float[5];
                    _lineStyle[0] = (float)3.0;
                    _lineStyle[1] = (float)3.0;
                    _lineStyle[0] = (float)5.0;
                    _lineStyle[1] = (float)3.0;
                    break;
            }
            _lineStyleSet = true;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  This function is used to specify a new line style array that doesn't
    //!  match any of the defined types.
    //--------------------------------------------------------------------------
    public void lineStyle( float[] newStyle ) {
        synchronized( this ) {
            _lineStyle = newStyle;
            _lineStyleSet = true;
        }
    }
    //--------------------------------------------------------------------------
    //!  Turn off any line style for this object.  Whatever style is inherited
    //!  from parent objects will be used for drawing.
    //--------------------------------------------------------------------------
    public void lineStyleOff() {
        synchronized( this ) {
            _lineStyleSet = false;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Make this object an "empty" type.  It can hold characteristics but it
    //!  doesn't draw anything.
    //--------------------------------------------------------------------------
    public void empty() {
        synchronized( this ) {
            type = EMPTY;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Make this object a "text" type.  The text position and string
    //!  representation are given.
    //--------------------------------------------------------------------------
    public void text( String newText, double x, double y ) {
        synchronized( this ) {
            type = TEXT;
            textString = newText;
            _x1 = x;
            _y1 = y;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  This text will be positioned at the "current point", which is given
    //!  by the most recent "vertex" call.
    //--------------------------------------------------------------------------
    public void text( String newText ) {
        synchronized( this ) {
            type = FLOATING_TEXT;
            textString = newText;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Make this object a "drawrect" type.  It will draw a rectangle at the
    //!  specified x and y position with the specified width and height.
    //--------------------------------------------------------------------------
    public void drawrect( double x, double y, double w, double h ) {
        synchronized( this ) {
            type = DRAWRECT;
            _shape = new Rectangle2D.Double( x, y, w, h );
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Make this object a "drawline" type.  It will draw a line between the
    //!  two specified points.
    //--------------------------------------------------------------------------
    public void drawline( double x1, double y1, double x2, double y2 ) {
        synchronized( this ) {
            type = DRAWLINE;
            _shape = new Line2D.Double( x1, y1, x2, y2 );
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Make this object a "drawpoly" type.  A line will be drawn starting at
    //!  the first specified point, through all of the others, to the last point.
    //!  Note that this object will not necessarily be "closed".
    //--------------------------------------------------------------------------
    public void drawpoly( double x[], double y[], int n ) {
        synchronized( this ) {
            type = DRAWPOLY;
            if ( n <= 0 )
                return;
            GeneralPath path = new GeneralPath();
            path.moveTo( x[0], y[0] );
            for ( int i = 1; i < n; ++i )
                path.lineTo( x[i], y[i] );
            _shape = path;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Make this object a "fillrect" type.  It will fill a rectangle with
    //!  color.
    //--------------------------------------------------------------------------
    public void fillrect( double x, double y, double w, double h ) {
        synchronized( this ) {
            type = FILLRECT;
            _shape = new Rectangle2D.Double( x, y, w, h );
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Make this object a "fillpoly" type.  The polygon specified by the list
    //!  of points will be filled.  Note that this object will not necessarily 
    //!  be "closed" unless the first and last points are identical.
    //--------------------------------------------------------------------------
    public void fillpoly( double x[], double y[], int n ) {
        synchronized( this ) {
            type = FILLPOLY;
            if ( n <= 0 )
                return;
            GeneralPath path = new GeneralPath();
            path.moveTo( x[0], y[0] );
            for ( int i = 1; i < n; ++i )
                path.lineTo( x[i], y[i] );
            _shape = path;
        }
    }

    //--------------------------------------------------------------------------
    //!  Add a curve to the current path.  The previous vertex point serves as
    //!  a starting position.
    //--------------------------------------------------------------------------
    public void curve( double x1, double y1, double x2, double y2, double x3, double y3 ) {
        synchronized( this ) {
            type = CURVE;
            _x1 = x1;
            _y1 = y1;
            _x2 = x2;
            _y2 = y2;
            _x3 = x3;
            _y3 = y3;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  This is starts a new compound path.
    //--------------------------------------------------------------------------
    public void newPath() {
        synchronized( this ) {
            type = NEWPATH;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Draw a line through the current path.
    //--------------------------------------------------------------------------
    public void stroke() {
        synchronized( this ) {
            type = STROKEPATH;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Fill the shape described by the current path.
    //--------------------------------------------------------------------------
    public void fill() {
        synchronized( this ) {
            type = FILLPATH;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Connect a line to the beginning of the current path.
    //--------------------------------------------------------------------------
    public void close() {
        synchronized( this ) {
            type = CLOSEPATH;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Add a vertex to the current path.
    //--------------------------------------------------------------------------
    public void vertex( double x, double y ) {
        synchronized( this ) {
            type = VERTEX;
            _x1 = x;
            _y1 = y;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Add a "relative" vertex to the current path.  The true position of
    //!  this vertex will depend on the previous vertex.
    //--------------------------------------------------------------------------
    public void relativeVertex( double x, double y ) {
        synchronized( this ) {
            type = RELATIVE_VERTEX;
            _x1 = x;
            _y1 = y;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Add an image - its top left corner will be at x,y.  To obtain a
    //!  BufferedImage from a file, try this:
    //!      import javax.imageio.ImageIO;
    //!      import java.awt.image.BufferedImage;
    //!      BufferedImage newImage = ImageIO.read( new File( "foo.jpeg" ) );
    //!  This ImageIO class supposedly understands a bunch of formats.  Seems
    //!  to work for JPEG at least.
    //--------------------------------------------------------------------------
    public void image( BufferedImage image, double x, double y ) {
        synchronized( this ) {
            type = DRAWIMAGE;
            _image = image;
            _x1 = x;
            _y1 = y;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  A "COMPLEX_TEXT" object is a container object for holding a number of
    //!  child objects (usually text, although this is not required).  It can
    //!  be left, center, or right justified.  Child objects are measured, then
    //!  drawn to fit the justification.
    //--------------------------------------------------------------------------
    public void complexText( int newJustify, double x, double y ) {
        synchronized( this ) {
            type = COMPLEX_TEXT;
            _justify = newJustify;
            _x1 = x;
            _y1 = y;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  This is a function for building a "COMPLEX_TEXT" object from a string
    //!  containing nested text instructions.  The instructions follow a sort
    //!  of XML-like convention where "on" and "off" instructions apply to text
    //!  between them.  The following instructions are recognized:
    //!
    //!     <bold> ... </bold>
    //!     <italic> ... </italic>
    //!     <font=FONT> ... </font>       FONT is MONO, SERIF, or SANS
    //!     <size=NUM> ... </size>        Makes font a muliple of its current size (0.5 e.g.)
    //!     <y=NUM> ... </y>              Move in y direction a multiple of font size
    //!     <color=0xRRGGBB> ... </color>
    //!
    //!  Note that there is NO EFFORT made to make this true XML.  
    //--------------------------------------------------------------------------
    public void complexText( int newJustify, double x, double y, String text ) {
        synchronized( this ) {
            type = COMPLEX_TEXT;
            _justify = newJustify;
            _x1 = x;
            _y1 = y;
            this.add( stringToTextObject( text ) );
        }
    }
    
    //--------------------------------------------------------------------------
    //!  This is complex text that is "floating" i.e. it starts at the most
    //!  recently defined position.
    //--------------------------------------------------------------------------
    public void complexText( int newJustify, String text ) {
        synchronized( this ) {
            _floatingText = true;
        }
        this.complexText( newJustify, 0.0, 0.0, text );
    }
    
    //--------------------------------------------------------------------------
    //!  This function is used by the "complexText()" function (above) to do
    //!  the actual parsing of text.  It breaks text into a series of DrawObjects
    //!  containing text and formatting commands.
    //--------------------------------------------------------------------------
    public DrawObject stringToTextObject( String text ) {
        //  We have to return some sort of object here...
        DrawObject thisObject = new DrawObject();
        //  Locate the outer-most formatting command, if there is any, but first
        //  finding the first instance of any of the commands we recognize.
        int boldIdx = text.indexOf( "<bold>" );
        int italicIdx = text.indexOf( "<italic>" );
        int fontIdx = text.indexOf( "<font" );
        int sizeIdx = text.indexOf( "<size" );
        int yIdx = text.indexOf( "<y" );
        int colorIdx = text.indexOf( "<color" );
        //  Now locate the smallest of these results that is not -1.  This will
        //  be the first command string.  When encountered, also locate its
        //  paired closing string.
        int parseIdx = -1;
        int endIdx = text.length();
        int commandLen = 0;
        int terminalLen = 0;
        if ( boldIdx < parseIdx || parseIdx == -1 ) {
            parseIdx = boldIdx;
            endIdx = text.lastIndexOf( "</bold>" );
            terminalLen = new String( "</bold>" ).length();
        }
        if ( ( italicIdx < parseIdx && italicIdx != -1 ) || parseIdx == -1 ) {
            parseIdx = italicIdx;
            endIdx = text.lastIndexOf( "</italic>" );
            terminalLen = new String( "</italic>" ).length();
        }
        if ( ( fontIdx < parseIdx && fontIdx != -1 ) || parseIdx == -1 ) {
            parseIdx = fontIdx;
            endIdx = text.lastIndexOf( "</font>" );
            terminalLen = new String( "</font>" ).length();
        }
        if ( ( sizeIdx < parseIdx && sizeIdx != -1 ) || parseIdx == -1 ) {
            parseIdx = sizeIdx;
            endIdx = text.lastIndexOf( "</size>" );
            terminalLen = new String( "</size>" ).length();
        }
        if ( ( yIdx < parseIdx && yIdx != -1 ) || parseIdx == -1 ) {
            parseIdx = yIdx;
            endIdx = text.lastIndexOf( "</y>" );
            terminalLen = new String( "</y>" ).length();
        }
        if ( ( colorIdx < parseIdx && colorIdx != -1 ) || parseIdx == -1 ) {
            parseIdx = colorIdx;
            endIdx = text.lastIndexOf( "</color>" );
            terminalLen = new String( "</color>" ).length();
        }
        //  Some possibilities here....there might be no command strings at all,
        //  in which this object is simply the content of the text.
        if ( parseIdx == -1 ) {
            thisObject.text( text );
            return thisObject;
        }
        //  We think we've detected a command.  To confirm, see if we can find
        //  the end of it.
        commandLen = text.substring( parseIdx ).indexOf( ">" ) + 1;
        //  If this failed assume the content is simple text again.
        if ( commandLen < 2 ) {
            thisObject.text( text );
        }
        //  Simple case...if the detected command is at the start of the text
        //  and the closing command is at the end, apply the command instruction
        //  to this object and submit the (non-command) text to a recursive call
        //  to this function.  This is the only place where commands are applied.
        //
        //  We also do this if we have a proper command start, but no end.
        if ( parseIdx == 0 && ( endIdx + terminalLen == text.length() || endIdx == -1 ) ) {
            //  Fix the situation where there is no terminating command.
            if ( endIdx == -1 )
                endIdx = text.length();
            int equalPos = text.substring( 0, commandLen ).indexOf( "=" );
            //  Apply the command to this object
            if ( parseIdx == boldIdx ) {
                thisObject.fontBold( true );
            }
            else if ( parseIdx == italicIdx ) {
                thisObject.fontItalic( true );
            }
            else if ( parseIdx == fontIdx && equalPos != -1 && commandLen - equalPos > 2 ) {
                //  Check the font name against those we know...
                if ( text.substring( equalPos + 1, commandLen - 1 ).trim().equalsIgnoreCase( "MONO" ) )
                    thisObject.fontName( "MONO" );
                else if ( text.substring( equalPos + 1, commandLen - 1 ).trim().equalsIgnoreCase( "SANS" ) )
                    thisObject.fontName( "SANS" );
                else if ( text.substring( equalPos + 1, commandLen - 1 ).trim().equalsIgnoreCase( "SERIF" ) )
                    thisObject.fontName( "SERIF" );
            }
            else if ( parseIdx == sizeIdx && equalPos != -1 && commandLen - equalPos > 2 ) {
                thisObject.fontScale( new Double( text.substring( equalPos + 1, commandLen - 1 ).trim() ).doubleValue() );
            }
            else if ( parseIdx == yIdx && equalPos != -1 && commandLen - equalPos > 2 ) {
                thisObject.fontY( new Double( text.substring( equalPos + 1, commandLen - 1 ).trim() ).doubleValue() );
            }
            else if ( parseIdx == colorIdx && equalPos != -1 && commandLen - equalPos > 2 ) {
                thisObject.color( new Color( Integer.decode( text.substring( equalPos + 1, commandLen - 1 ).trim() ).intValue() ) );
            }
            //  Add the text as a child object
            thisObject.add( stringToTextObject( text.substring( commandLen, endIdx ) ) );
            return thisObject;
        }
        //  If the above is not true, we want to break our text into two parts...
        //  the text before the command (which should be plain text):
        if ( parseIdx != 0 )
            thisObject.add( stringToTextObject( text.substring( 0, parseIdx ) ) );
        //  The stuff inside the command:
        thisObject.add( stringToTextObject( text.substring( parseIdx, endIdx + terminalLen ) ) );
        //  And the stuff after the command:
        thisObject.add( stringToTextObject( text.substring( endIdx + terminalLen, text.length() ) ) );
        //System.out.println( "don't know what to do: \"" + text + "\"" );
        return thisObject;
    }
    
    //--------------------------------------------------------------------------
    //!  Set the name of this object.
    //--------------------------------------------------------------------------
    public void name( String newName ) {
        _name = newName;
    }
    
    //--------------------------------------------------------------------------
    //!  Return the name of this object.
    //--------------------------------------------------------------------------
    public String name() {
        return _name;
    }
    
    //--------------------------------------------------------------------------
    //!  Make this object visible or not.
    //--------------------------------------------------------------------------
    public void visible( boolean newVal ) {
        synchronized( this ) {
            _visible = newVal;
        }
    }
    
    //--------------------------------------------------------------------------
    //!  Determine whether this object is visible or not.
    //--------------------------------------------------------------------------
    public boolean visible() {
        return _visible;
    }
    
    /*
     * Make this object unscaled - i.e. the scale will be adjusted such that a
     * pixel is a pixel.
     */
    public void unscaled( boolean newVal ) {
        _unscaled = newVal;
    }
    public boolean unscaled() {
        return _unscaled;
    }
    
    double _xScale;
    double _yScale;
    boolean _scaleSet;
    double _xOff;
    double _yOff;
    boolean _translateSet;
    double _rotate;
    boolean _rotateSet;
    Font _theFont;
    String _font;
    boolean _fontSet;
    double _fontSize;
    Double _fontScale;
    Double _fontY;
    String _fontName;
    boolean _fontBold;
    boolean _fontItalic;
    boolean _clipSet;
    Color _color;
    boolean _colorSet;
    double _lineWidth;
    boolean _lineWidthSet;
    int _lineCap;
    boolean _lineCapSet;
    int _lineJoin;
    boolean _lineJoinSet;
    float[] _lineStyle;
    boolean _lineStyleSet;
    boolean _unscaled;
    
    //  These are variables used by different types of objects.  Not all of them
    //  will necessarily be employed by a given object.
    String textString;
    double _x1;
    double _y1;
    double _x2;
    double _y2;
    double _x3;
    double _y3;
    Shape _shape;
    Shape _clipShape;
    BufferedImage _image;
    int _justify;
    boolean _floatingText;
    
    int type;
    
    //  The "name" of an object is not actually used for drawing - however it
    //  can be used to identify an object.  Generally it is not expected to be
    //  set.
    protected String _name;
    
    //  This flag will deterine whether this object (and its children) are
    //  drawn or not.  This is an easy way of eliminating an object and its
    //  children from a drawing list without removing it.
    protected boolean _visible;
    
    //  These are the different drawing object types.
    static final int EMPTY           = 0;
    static final int TEXT            = 1;
    static final int NEWPATH         = 2;
    static final int VERTEX          = 3;
    static final int CURVE           = 4;
    static final int STROKEPATH      = 5;
    static final int FILLPATH        = 6;
    static final int DRAWLINE        = 7;
    static final int DRAWRECT        = 8;
    static final int FILLRECT        = 9;
    static final int DRAWPOLY        = 10;
    static final int FILLPOLY        = 11;
    static final int DRAWIMAGE       = 12;
    static final int CLOSEPATH       = 13;
    static final int COMPLEX_TEXT    = 14;
    static final int FLOATING_TEXT   = 15;
    static final int RELATIVE_VERTEX = 16;
    
    //  These are the different line caps, joints, and styles.
    static final int CAP_FLAT   = 0;
    static final int CAP_ROUND  = 1;
    static final int CAP_SQUARE = 2;
    static final int JOIN_FLAT  = 0;
    static final int JOIN_ROUND = 1;
    static final int JOIN_BEVEL = 2;
    static final int LINE_SOLID = 0;
    static final int LINE_DASH  = 1;
    static final int LINE_DOT   = 2;
    static final int LINE_DASHDOT = 3;
    
    //  These are for text justification
    public static final int LEFT_JUSTIFY   = 0;
    public static final int RIGHT_JUSTIFY  = 1;
    public static final int CENTER_JUSTIFY = 2;
    
    double drawfontSize;
    Graphics2D _drawGraphics;
    
}
