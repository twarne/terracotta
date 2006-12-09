require "rexml/parent"
require "rexml/parseexception"
require "rexml/namespace"
require 'rexml/entity'
require 'rexml/attlistdecl'
require 'rexml/xmltokens'

module REXML
	# Represents an XML DOCTYPE declaration; that is, the contents of <!DOCTYPE
	# ... >.  DOCTYPES can be used to declare the DTD of a document, as well as
	# being used to declare entities used in the document.
	class DocType < Parent
		include XMLTokens
		START = "<!DOCTYPE"
		STOP = ">"
		SYSTEM = "SYSTEM"
		PUBLIC = "PUBLIC"
		DEFAULT_ENTITIES = { 
			'gt'=>EntityConst::GT, 
			'lt'=>EntityConst::LT, 
			'quot'=>EntityConst::QUOT, 
			"apos"=>EntityConst::APOS 
		}

		# name is the name of the doctype
		# external_id is the referenced DTD, if given
		attr_reader :name, :external_id, :entities, :namespaces

		# Constructor
		#
		#	 dt = DocType.new( 'foo', '-//I/Hate/External/IDs' )
		#	 # <!DOCTYPE foo '-//I/Hate/External/IDs'>
		#	 dt = DocType.new( doctype_to_clone )
		#	 # Incomplete.  Shallow clone of doctype
    #
    # +Note+ that the constructor: 
    #
    #  Doctype.new( Source.new( "<!DOCTYPE foo 'bar'>" ) )
    #
    # is _deprecated_.  Do not use it.  It will probably disappear.
		def initialize( first, parent=nil )
			@entities = DEFAULT_ENTITIES
			@long_name = @uri = nil
			if first.kind_of? String
				super()
				@name = first
				@external_id = parent
			elsif first.kind_of? DocType
				super( parent )
				@name = first.name
				@external_id = first.external_id
			elsif first.kind_of? Array
				super( parent )
				@name = first[0]
				@external_id = first[1]
				@long_name = first[2]
				@uri = first[3]
      elsif first.kind_of? Source
        super( parent )
        parser = Parsers::BaseParser.new( first )
        event = parser.pull
        if event[0] == :start_doctype
          @name, @external_id, @long_name, @uri, = event[1..-1]
        end
      else
        super()
			end
		end

		def node_type
			:doctype
		end

		def attributes_of element
			rv = []
			each do |child|
				child.each do |key,val|
					rv << Attribute.new(key,val)
				end if child.kind_of? AttlistDecl and child.element_name == element
			end
			rv
		end

		def attribute_of element, attribute
			att_decl = find do |child|
				child.kind_of? AttlistDecl and
				child.element_name == element and
				child.include? attribute
			end
			return nil unless att_decl
			att_decl[attribute]
		end

		def clone
			DocType.new self
		end

		# output::
		#   Where to write the string
		# indent::
		#   An integer.  If -1, no indenting will be used; otherwise, the
		#   indentation will be this number of spaces, and children will be
		#   indented an additional amount.
		# transitive::
		#   If transitive is true and indent is >= 0, then the output will be
		#   pretty-printed in such a way that the added whitespace does not affect
		#   the absolute *value* of the document -- that is, it leaves the value
		#   and number of Text nodes in the document unchanged.
		# ie_hack::
		#   Internet Explorer is the worst piece of crap to have ever been
		#   written, with the possible exception of Windows itself.  Since IE is
		#   unable to parse proper XML, we have to provide a hack to generate XML
		#   that IE's limited abilities can handle.  This hack inserts a space 
		#   before the /> on empty tags.
		#
		def write( output, indent=0, transitive=false, ie_hack=false )
			indent( output, indent )
			output << START
			output << ' '
			output << @name
			output << " #@external_id" if @external_id
			output << " #@long_name" if @long_name
			output << " #@uri" if @uri
			unless @children.empty?
				next_indent = indent + 1
				output << ' ['
				child = nil		# speed
				@children.each { |child|
					output << "\n"
					child.write( output, next_indent )
				}
				output << "\n"
				#output << '   '*next_indent
				output << "]"
			end
			output << STOP
		end

    def context
      @parent.context
    end

		def entity( name )
			@entities[name].unnormalized if @entities[name]
		end

		def add child
			super(child)
			@entities = DEFAULT_ENTITIES.clone if @entities == DEFAULT_ENTITIES
			@entities[ child.name ] = child if child.kind_of? Entity
		end
	end

	# We don't really handle any of these since we're not a validating
	# parser, so we can be pretty dumb about them.  All we need to be able
	# to do is spew them back out on a write()

	# This is an abstract class.  You never use this directly; it serves as a
	# parent class for the specific declarations.
	class Declaration < Child
		def initialize src
			super()
			@string = src
		end

		def to_s
			@string+'>'
		end

		def write( output, indent )
			output << ('   '*indent) if indent > 0
			output << to_s
		end
	end
	
	public
	class ElementDecl < Declaration
		def initialize( src )
			super
		end
	end

	class ExternalEntity < Child
		def initialize( src )
			super()
			@entity = src
		end
		def to_s
			@entity
		end
		def write( output, indent )
			output << @entity
			output << "\n"
		end
	end

	class NotationDecl < Child
		def initialize name, middle, rest
			@name = name
			@middle = middle
			@rest = rest
		end

		def to_s
			"<!NOTATION #@name '#@middle #@rest'>"
		end

		def write( output, indent=-1 )
			output << ('   '*indent) if indent > 0
			output << to_s
		end
	end
end
